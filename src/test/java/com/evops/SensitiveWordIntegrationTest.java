package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加工单文本入口敏感词管控集成测试：
 * 全角半角归一、分隔符插空命中、命中位置回传、分级拦截/打码、词库热更新、命中留痕。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SensitiveWordIntegrationTest.TestShiroBypass.class)
class SensitiveWordIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    private static final Path WORD_FILE = Paths.get("./target/test-sensitive-words.txt");

    @Resource
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private long operatorSeq = 5000;

    @BeforeEach
    void loadWordLibrary() throws Exception {
        Files.write(WORD_FILE, Arrays.asList(
                "# 测试词库",
                "假药|BLOCK",
                "vip|MASK",
                "黄牛|MASK"), StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/sensitive-words/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.wordCount").value(3));
    }

    // ---------------- 扫描与命中位置 ----------------

    @Test
    void scan_normalizes_fullwidth_and_separators_and_returns_positions() throws Exception {
        // 全角字母归一：ＶＩＰ 命中 vip；分隔符插空：假-药 命中 假药
        JsonNode data = scan("要ＶＩＰ假-药");
        assertEquals(2, data.get("hitCount").asInt());

        JsonNode vip = hitOf(data, "vip");
        assertEquals(1, vip.get("start").asInt());
        assertEquals(4, vip.get("end").asInt());
        assertEquals("ＶＩＰ", vip.get("surface").asText());
        assertEquals("MASK", vip.get("level").asText());

        JsonNode jiaYao = hitOf(data, "假药");
        assertEquals(4, jiaYao.get("start").asInt());
        assertEquals(7, jiaYao.get("end").asInt());
        assertEquals("假-药", jiaYao.get("surface").asText());
        assertEquals("BLOCK", jiaYao.get("level").asText());

        // 全角空格插空同样命中
        JsonNode data2 = scan("黄　牛");
        assertEquals(1, data2.get("hitCount").asInt());
        assertEquals("黄　牛", hitOf(data2, "黄牛").get("surface").asText());
    }

    // ---------------- 分级：拦截 ----------------

    @Test
    void block_level_rejects_order_and_leaves_hit_log() throws Exception {
        long lensId = createLens("L-SW-BLK", "SW-B1");
        String body = orderBody("O-SW-BLK", lensId, "客户要买假药");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "5001").header("X-Request-No", "SW-B2")
                        .content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SENSITIVE_WORD_BLOCKED"))
                .andExpect(jsonPath("$.message").value("文本包含拦截级敏感词，已拦截：假药（remark[4,6)）"));

        // 整单不落地
        Long orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order WHERE order_no='O-SW-BLK'", Long.class);
        assertEquals(0L, orders);

        // 拦截留痕：独立事务落库，加工单未建成 biz_id 为空
        Long logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_hit_log WHERE order_no='O-SW-BLK'"
                        + " AND action_taken='BLOCK' AND biz_id IS NULL AND hit_word='假药'"
                        + " AND field_name='remark' AND start_index=4 AND end_index=6",
                Long.class);
        assertEquals(1L, logs);
    }

    // ---------------- 分级：打码 ----------------

    @Test
    void mask_level_stores_masked_text_and_logs_with_order() throws Exception {
        long lensId = createLens("L-SW-MSK", "SW-M1");
        String body = orderBody("O-SW-MSK", lensId, "找黄牛挂号");
        JsonNode created = performJson(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "5002").header("X-Request-No", "SW-M2")
                .content(body));
        long orderId = created.get("data").get("id").asLong();

        // 落库为打码后的文本
        String remark = jdbcTemplate.queryForObject(
                "SELECT remark FROM t_process_order WHERE id=?", String.class, orderId);
        assertEquals("找**挂号", remark);

        // 打码命中记录与加工单关联（同事务）
        Long logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_hit_log WHERE biz_id=? AND action_taken='MASK'"
                        + " AND hit_word='黄牛' AND start_index=1 AND end_index=3",
                Long.class, orderId);
        assertEquals(1L, logs);
    }

    @Test
    void update_order_text_is_scanned_too() throws Exception {
        long lensId = createLens("L-SW-UPD", "SW-U1");
        JsonNode created = performJson(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", "5003").header("X-Request-No", "SW-U2")
                .content(orderBody("O-SW-UPD", lensId, "正常备注")));
        long orderId = created.get("data").get("id").asLong();
        long version = created.get("data").get("versionNo").asLong();

        // 打码级：修改后落库为打码文本
        String updateBody = "{\"id\":" + orderId + ",\"versionNo\":" + version + ","
                + "\"remark\":\"加vip服务\",\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},{\"eye\":\"R\",\"sphere\":-1.00}]}";
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "5003").header("X-Request-No", "SW-U3")
                        .content(updateBody))
                .andExpect(jsonPath("$.code").value("OK"));
        String remark = jdbcTemplate.queryForObject(
                "SELECT remark FROM t_process_order WHERE id=?", String.class, orderId);
        assertEquals("加***服务", remark);

        // 拦截级：修改被拒绝并留痕
        String blockBody = "{\"id\":" + orderId + ",\"versionNo\":" + (version + 1) + ","
                + "\"remark\":\"这是假药\",\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},{\"eye\":\"R\",\"sphere\":-1.00}]}";
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "5003").header("X-Request-No", "SW-U4")
                        .content(blockBody))
                .andExpect(jsonPath("$.code").value("SENSITIVE_WORD_BLOCKED"));
        Long logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_hit_log WHERE order_no='O-SW-UPD'"
                        + " AND action_taken='BLOCK' AND hit_word='假药'",
                Long.class);
        assertEquals(1L, logs);
    }

    // ---------------- 词库热更新 ----------------

    @Test
    void word_library_hot_reload_picks_up_changes() throws Exception {
        // 换成另一份词库，不重启直接 reload 生效
        Files.write(WORD_FILE, Arrays.asList("乙词|MASK"), StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/sensitive-words/reload"))
                .andExpect(jsonPath("$.data.wordCount").value(1));

        assertEquals(0, scan("假药").get("hitCount").asInt());
        assertEquals(1, scan("甲乙词").get("hitCount").asInt());
    }

    // ---------------- 辅助方法 ----------------

    private JsonNode scan(String text) throws Exception {
        String resp = mockMvc.perform(post("/api/sensitive-words/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(resp).get("data");
    }

    private JsonNode hitOf(JsonNode scanData, String word) {
        for (JsonNode hit : scanData.get("hits")) {
            if (word.equals(hit.get("word").asText())) {
                return hit;
            }
        }
        throw new AssertionError("未命中词条：" + word + "，实际命中：" + scanData.get("hits"));
    }

    private long createLens(String code, String reqNo) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"镜片" + code + "\",\"price\":399.00}";
        JsonNode node = performJson(post("/api/lens")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", reqNo)
                .content(body));
        return node.get("data").get("id").asLong();
    }

    private String orderBody(String orderNo, long lensId, String remark) {
        return "{\"orderNo\":\"" + orderNo + "\",\"lensId\":" + lensId
                + ",\"customerName\":\"张三\",\"storeCode\":\"SSW\","
                + "\"remark\":\"" + remark + "\",\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},"
                + "{\"eye\":\"R\",\"sphere\":-1.00}]}";
    }

    private JsonNode performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
