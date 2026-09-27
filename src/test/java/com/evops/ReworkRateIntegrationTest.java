package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修流转与返修率反向核对集成测试：
 * READY→PROCESSING 返修流转留痕、以加工单底账为准的返修率统计、兄弟组结果反向比对。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ReworkRateIntegrationTest.TestShiroBypass.class)
class ReworkRateIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    @Resource
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private long operatorSeq = 7000;

    // ---------------- 返修流转 ----------------

    @Test
    void rework_transits_ready_back_to_processing_with_log() throws Exception {
        long lensId = createLens("L-RW-1", "RW-1");
        long orderId = createOrder("O-RW-1", lensId, "RW-2", "SRW-F");

        act(orderId, "start", "RW-3", "PROCESSING");
        act(orderId, "finish", "RW-4", "READY");
        // 成品复检不通过：待取镜 → 加工中
        act(orderId, "rework", "RW-5", "PROCESSING");
        // 再次加工完成后取镜
        act(orderId, "finish", "RW-6", "READY");
        act(orderId, "pickup", "RW-7", "PICKED_UP");

        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.statusLogs[*].operType").value(
                        org.hamcrest.Matchers.hasItems("REWORK")));

        // 已取镜（终态）不可返修
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", "7001").header("X-Request-No", "RW-8"))
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_ILLEGAL"));

        // 已建档不能直接返修（必须先加工完成到待取镜）
        long orderId2 = createOrder("O-RW-2", lensId, "RW-9", "SRW-F");
        mockMvc.perform(post("/api/orders/" + orderId2 + "/rework")
                        .header("X-Operator-Id", "7001").header("X-Request-No", "RW-10"))
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_ILLEGAL"));
        assertEquals("CREATED", orderStatus(orderId2));
    }

    // ---------------- 返修率统计与反向核对 ----------------

    @Test
    void rework_rate_stats_and_reverse_reconcile() throws Exception {
        long lensId = createLens("L-RW-S", "RW-S1");
        // 门店 SRW-S 三单：S1 返修两次，S2 正常完工，S3 仅建档
        long s1 = createOrder("O-RW-S1", lensId, "RW-S2", "SRW-S");
        act(s1, "start", "RW-S3", "PROCESSING");
        act(s1, "finish", "RW-S4", "READY");
        act(s1, "rework", "RW-S5", "PROCESSING");
        act(s1, "finish", "RW-S6", "READY");
        act(s1, "rework", "RW-S7", "PROCESSING");

        long s2 = createOrder("O-RW-S2", lensId, "RW-S8", "SRW-S");
        act(s2, "start", "RW-S9", "PROCESSING");
        act(s2, "finish", "RW-S10", "READY");

        createOrder("O-RW-S3", lensId, "RW-S11", "SRW-S");

        // 以加工单底账为准：3 单建档，1 单返修，共返修 2 次
        mockMvc.perform(get("/api/orders/stats/rework-rate").param("storeCode", "SRW-S"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOrders").value(3))
                .andExpect(jsonPath("$.data.reworkOrders").value(1))
                .andExpect(jsonPath("$.data.reworkTimes").value(2))
                .andExpect(jsonPath("$.data.reworkRate").value(0.3333))
                .andExpect(jsonPath("$.data.reworkOrderNos[0]").value("O-RW-S1"));

        // 反向核对：对方结果与底账一致
        reconcile("{\"storeCode\":\"SRW-S\",\"totalOrders\":3,\"reworkOrders\":1,"
                        + "\"reworkOrderNos\":[\"O-RW-S1\"]}")
                .andExpect(jsonPath("$.data.consistent").value(true));

        // 对方返修单数算多了：不一致且给出差值
        reconcile("{\"storeCode\":\"SRW-S\",\"totalOrders\":3,\"reworkOrders\":2}")
                .andExpect(jsonPath("$.data.consistent").value(false))
                .andExpect(jsonPath("$.data.ours.reworkOrders").value(1))
                .andExpect(jsonPath("$.data.diff.reworkOrdersDiff").value(-1));

        // 对方漏算了返修单：给出 missing 差集
        reconcile("{\"storeCode\":\"SRW-S\",\"totalOrders\":3,\"reworkOrders\":1,"
                        + "\"reworkOrderNos\":[]}")
                .andExpect(jsonPath("$.data.consistent").value(false))
                .andExpect(jsonPath("$.data.diff.missingInTheirs[0]").value("O-RW-S1"));

        // 对方多算了返修单：给出 extra 差集
        reconcile("{\"storeCode\":\"SRW-S\",\"totalOrders\":3,\"reworkOrders\":2,"
                        + "\"reworkOrderNos\":[\"O-RW-S1\",\"O-RW-S2\"]}")
                .andExpect(jsonPath("$.data.consistent").value(false))
                .andExpect(jsonPath("$.data.diff.extraInTheirs[0]").value("O-RW-S2"));
    }

    // ---------------- 辅助方法 ----------------

    private org.springframework.test.web.servlet.ResultActions reconcile(String body) throws Exception {
        return mockMvc.perform(post("/api/orders/stats/rework-rate/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "7002").header("X-Request-No", "RW-RC-" + body.hashCode())
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
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

    private long createOrder(String orderNo, long lensId, String reqNo, String storeCode) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"lensId\":" + lensId
                + ",\"customerName\":\"张三\",\"storeCode\":\"" + storeCode + "\",\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},"
                + "{\"eye\":\"R\",\"sphere\":-1.00}]}";
        JsonNode node = performJson(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", reqNo)
                .content(body));
        return node.get("data").get("id").asLong();
    }

    private void act(long orderId, String action, String reqNo, String expectedStatus) throws Exception {
        mockMvc.perform(post("/api/orders/" + orderId + "/" + action)
                        .header("X-Operator-Id", String.valueOf(++operatorSeq))
                        .header("X-Request-No", reqNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderStatus").value(expectedStatus));
    }

    private JsonNode performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private String orderStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status FROM t_process_order WHERE id=?", String.class, id);
    }
}
