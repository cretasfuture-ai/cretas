package com.cretas.aims.service.scale.sim;

import com.cretas.aims.entity.scale.ScaleProtocolConfig;
import com.cretas.aims.repository.ScaleBrandModelRepository;
import com.cretas.aims.repository.ScaleProtocolConfigRepository;
import com.cretas.aims.repository.ScaleProtocolTestCaseRepository;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 秤模拟器测试公用件: 读模拟器生成的 fixtures/frames.json, 读种子 SQL 里的内置协议配置。
 * 配置不拷贝一份放测试资源 (拷贝会漂), 直接从种子 SQL 抠出来。
 */
public final class ScaleSimSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path FIXTURES = Path.of("..", "..", "..", "tools", "scale-simulator", "fixtures", "frames.json");
    static final Path SEED_SQL = Path.of("src", "main", "resources", "db", "migration-pg-converted",
            "V2026_01_04_11__scale_initial_data.sql");

    private ScaleSimSupport() {}

    public record Fixture(String protocol, String scenario, String hex, JsonNode expect) {
        public byte[] bytes() { return HexFormat.of().parseHex(hex); }
        public boolean ok() { return expect.path("ok").asBoolean(); }
        @Override public String toString() { return protocol + "/" + scenario; }
    }

    public static List<Fixture> fixtures() {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(FIXTURES, StandardCharsets.UTF_8));
            List<Fixture> out = new ArrayList<>();
            for (JsonNode n : root) {
                out.add(new Fixture(n.get("protocol").asText(), n.get("scenario").asText(),
                        n.get("hex").asText(), n.get("expect")));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("读不到模拟器 fixtures: " + FIXTURES.toAbsolutePath(), e);
        }
    }

    /** 从种子 SQL 里取某内置协议的 frame_format JSON (按 id 定位, 还原 SQL 的 '' 转义)。 */
    public static String seedFrameFormat(String protocolId) {
        try {
            String sql = Files.readString(SEED_SQL, StandardCharsets.UTF_8);
            int at = sql.indexOf("'" + protocolId + "'");
            if (at < 0) throw new IllegalStateException("种子 SQL 里没有 " + protocolId);
            // serial/api/modbus 配置也是 '{ 开头, 要挑含 frameType 的那个
            int ft = sql.indexOf("\"frameType\"", at);
            int open = sql.lastIndexOf("'{", ft);
            int close = sql.indexOf("}'", ft) + 1;
            return sql.substring(open + 1, close).replace("''", "'");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String proposedYaohuaFrameFormat() {
        return proposed("yaohua");
    }

    private static String proposed(String name) {
        try (var in = ScaleSimSupport.class.getResourceAsStream("/scale-sim/" + name + "-proposed-frame-format.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ScaleProtocolConfig config(String code, String frameFormat) {
        return ScaleProtocolConfig.builder().id("sim-" + code).protocolCode(code)
                .protocolName(code).frameFormat(frameFormat).build();
    }

    /** 线上实际使用的解析入口 (ScaleProtocolAdapterServiceImpl), 仓库层用桩。 */
    public static ScaleProtocolAdapterServiceImpl adapter(ScaleProtocolConfig... configs) {
        ScaleProtocolConfigRepository repo = mock(ScaleProtocolConfigRepository.class);
        when(repo.findByProtocolCode(anyString())).thenReturn(Optional.empty());
        for (ScaleProtocolConfig c : configs) {
            when(repo.findByProtocolCode(c.getProtocolCode())).thenReturn(Optional.of(c));
        }
        return new ScaleProtocolAdapterServiceImpl(repo, mock(ScaleBrandModelRepository.class),
                mock(ScaleProtocolTestCaseRepository.class), MAPPER);
    }

    /** 模拟器三条协议的配置: 柯力 / Modbus 取种子; 耀华用「按样例帧对齐」的拟议配置 (种子本身对不上, 见现状文档与 ScaleSeedConformanceContractTest)。 */
    public static ScaleProtocolAdapterServiceImpl simAdapter() {
        return adapter(
                config("KELI_D2008_ASCII", seedFrameFormat("proto-keli-d2008-ascii")),
                config("YAOHUA_XK3190_ASCII", proposedYaohuaFrameFormat()),
                config("MODBUS_RTU_STANDARD", seedFrameFormat("proto-modbus-rtu-standard")));
    }
}
