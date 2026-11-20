package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scale.*;
import com.cretas.aims.entity.scale.*;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.ScaleProtocolAdapterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 秤协议适配器服务实现
 *
 * @author Cretas Team
 * @since 2026-01-04
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScaleProtocolAdapterServiceImpl implements ScaleProtocolAdapterService {

    private final ScaleProtocolConfigRepository protocolRepository;
    private final ScaleBrandModelRepository brandModelRepository;
    private final ScaleProtocolTestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    // ==================== 协议配置管理 ====================

    @Override
    public List<ScaleProtocolDTO> getAvailableProtocols(String factoryId) {
        return protocolRepository.findAvailableProtocols(factoryId)
                .stream()
                .map(ScaleProtocolDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ScaleProtocolDTO getProtocolById(String protocolId) {
        return protocolRepository.findById(protocolId)
                .map(ScaleProtocolDTO::fromEntity)
                .orElse(null);
    }

    @Override
    public ScaleProtocolDTO getProtocolByCode(String protocolCode) {
        return protocolRepository.findByProtocolCode(protocolCode)
                .map(ScaleProtocolDTO::fromEntity)
                .orElse(null);
    }

    @Override
    public List<ScaleProtocolDTO> getBuiltinProtocols() {
        return protocolRepository.findByIsBuiltinTrue()
                .stream()
                .map(ScaleProtocolDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ==================== 品牌型号管理 ====================

    @Override
    public List<ScaleBrandModelDTO> getAllBrandModels() {
        return brandModelRepository.findAll()
                .stream()
                .map(ScaleBrandModelDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<ScaleBrandModelDTO> getRecommendedBrandModels() {
        return brandModelRepository.findByIsRecommendedTrueOrderByRecommendationScoreDesc()
                .stream()
                .map(ScaleBrandModelDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<ScaleBrandModelDTO> getBrandModelsByType(String scaleType) {
        try {
            ScaleBrandModel.ScaleType type = ScaleBrandModel.ScaleType.valueOf(scaleType.toUpperCase());
            return brandModelRepository.findByScaleType(type)
                    .stream()
                    .map(ScaleBrandModelDTO::fromEntity)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid scale type: {}", scaleType);
            return Collections.emptyList();
        }
    }

    @Override
    public List<ScaleBrandModelDTO> searchBrandModels(String keyword) {
        return brandModelRepository.searchByKeyword(keyword)
                .stream()
                .map(ScaleBrandModelDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ==================== 数据解析 ====================

    @Override
    public ScaleDataParseResult parseScaleData(String protocolId, byte[] rawData) {
        Optional<ScaleProtocolConfig> configOpt = protocolRepository.findById(protocolId);
        if (configOpt.isEmpty()) {
            return ScaleDataParseResult.error("PROTOCOL_NOT_FOUND", "协议不存在: " + protocolId);
        }

        ScaleProtocolConfig config = configOpt.get();
        return parseWithConfig(config, rawData);
    }

    @Override
    public ScaleDataParseResult parseScaleDataByCode(String protocolCode, byte[] rawData) {
        Optional<ScaleProtocolConfig> configOpt = protocolRepository.findByProtocolCode(protocolCode);
        if (configOpt.isEmpty()) {
            return ScaleDataParseResult.error("PROTOCOL_NOT_FOUND", "协议不存在: " + protocolCode);
        }

        ScaleProtocolConfig config = configOpt.get();
        return parseWithConfig(config, rawData);
    }

    @Override
    public ScaleDataParseResult parseScaleDataHex(String protocolId, String hexData) {
        byte[] rawData = hexToBytes(hexData);
        return parseScaleData(protocolId, rawData);
    }

    @Override
    public ScaleDataParseResult dryRunParse(String protocolId, String testDataHex) {
        return parseScaleDataHex(protocolId, testDataHex);
    }

    /**
     * 根据协议配置解析数据
     */
    private ScaleDataParseResult parseWithConfig(ScaleProtocolConfig config, byte[] rawData) {
        try {
            String frameFormat = config.getFrameFormat();
            if (frameFormat == null || frameFormat.isEmpty()) {
                return ScaleDataParseResult.error("INVALID_CONFIG", "协议帧格式未配置");
            }

            JsonNode formatNode = objectMapper.readTree(frameFormat);
            String frameType = formatNode.path("frameType").asText("ASCII_FIXED");

            ScaleDataParseResult result;

            switch (frameType) {
                case "ASCII_FIXED":
                    result = parseAsciiFixedFrame(formatNode, rawData);
                    break;
                case "ASCII_VARIABLE":
                    result = parseAsciiVariableFrame(formatNode, rawData);
                    break;
                case "HEX_FIXED":
                    result = parseHexFixedFrame(formatNode, rawData);
                    break;
                case "MODBUS_RTU":
                    result = parseModbusRtuFrame(formatNode, rawData);
                    break;
                default:
                    return ScaleDataParseResult.error("UNSUPPORTED_FRAME_TYPE", "不支持的帧类型: " + frameType);
            }

            if (result.isSuccess() && result.getWeight() == null) {
                // 帧格式配置对不上 (没有 weight 字段 / 下标越界) 也算成功会让下游把「没读到」当成读数
                return ScaleDataParseResult.error("NO_WEIGHT", "帧解析成功但没有取到重量 (检查协议字段配置)");
            }

            if (result.isSuccess()) {
                result.setProtocolId(config.getId());
                result.setProtocolName(config.getProtocolName());
                result.setRawDataHex(bytesToHex(rawData));
            }

            return result;

        } catch (Exception e) {
            log.error("Parse error for protocol {}: {}", config.getProtocolCode(), e.getMessage(), e);
            return ScaleDataParseResult.error("PARSE_EXCEPTION", e.getMessage());
        }
    }

    /**
     * 解析 ASCII 定长帧 (柯力/耀华等标准协议)
     * 格式示例: +001240 kg S\r\n
     *
     * <p>严格解析: 一帧里任何一处对不上就整帧拒绝, 绝不「尽量读」。拒绝的帧没有重量, 更不会是 0。
     * 校验: 可打印字符 / 起止标记 / 字段越界 / 符号只能 +- / 重量只能是数字(超量程单独报 OVERRANGE) /
     * 稳定标志必须在 mapping 里 / 必须读到重量。
     */
    private ScaleDataParseResult parseAsciiFixedFrame(JsonNode format, byte[] rawData) {
        try {
            String startMarker = frameMarker(format, "startMarker");
            String endMarker = frameMarker(format, "endMarker");
            // 配置里声明的起止标记允许含控制字符 (STX/ETX); 其余只放行可打印字符和 CR/LF
            for (byte b : rawData) {
                boolean ok = b == 0x0D || b == 0x0A || (b >= 0x20 && b <= 0x7E)
                        || startMarker.indexOf((char) (b & 0xFF)) >= 0 || endMarker.indexOf((char) (b & 0xFF)) >= 0;
                if (!ok) {
                    return ScaleDataParseResult.error("FRAME_INVALID_CHARS", "数据帧含非 ASCII 可打印字符");
                }
            }
            String ascii = new String(rawData, StandardCharsets.US_ASCII);

            if (!startMarker.isEmpty() && !ascii.startsWith(startMarker)) {
                return ScaleDataParseResult.error("FRAME_MARKER", "数据帧缺少起始标记");
            }
            // 换行类结束符 (CRLF / CR / LF) 是可选的: 设备可能只发 CR 或 LF, 网关可能已剥掉, 界面试解析也可能没贴尾部。
            // 少了结尾导致的「半帧」由字段越界检查拦住。其它结束标记 (如 ETX) 仍然必须有。
            boolean newlineOnlyEnd = !endMarker.isEmpty() && endMarker.chars().allMatch(c -> c == '\r' || c == '\n');
            if (!endMarker.isEmpty() && !newlineOnlyEnd && !ascii.endsWith(endMarker)) {
                return ScaleDataParseResult.error("FRAME_MARKER", "数据帧缺少结束标记 (可能只收到半帧)");
            }

            JsonNode fields = format.path("fields");

            BigDecimal weight = null;
            String unit = "kg";
            Boolean stable = null;
            char sign = '+';
            boolean weightNegative = false;
            boolean signFieldSeen = false;
            boolean weightCarriesSign = false;
            int fieldsEnd = 0;

            for (JsonNode field : fields) {
                String name = field.path("name").asText();
                int start = field.path("start").asInt();
                int length = field.path("length").asInt();

                if (start + length > ascii.length()) {
                    return ScaleDataParseResult.error("FRAME_TOO_SHORT",
                            "数据帧长度不足: 需要 " + (start + length) + ", 实际 " + ascii.length());
                }
                fieldsEnd = Math.max(fieldsEnd, start + length);

                String value = ascii.substring(start, start + length);

                switch (name) {
                    case "sign":
                        signFieldSeen = true;
                        sign = value.charAt(0);
                        if (sign != '+' && sign != '-') {
                            return ScaleDataParseResult.error("INVALID_SIGN", "符号位不是 +/-: " + value);
                        }
                        break;
                    case "weight":
                        String weightStr = value.trim();
                        if (weightStr.matches("[OoLl]+")) {
                            return ScaleDataParseResult.error("OVERRANGE", "秤报超量程/溢出: " + weightStr);
                        }
                        // 允许重量域自带符号 ("-12.5") 和 "12." / ".5" 写法
                        if (!weightStr.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)")) {
                            return ScaleDataParseResult.error("INVALID_WEIGHT", "无法解析重量值: '" + weightStr + "'");
                        }
                        if (weightStr.startsWith("+") || weightStr.startsWith("-")) {
                            weightCarriesSign = true;
                        }
                        if (weightStr.startsWith("-")) {
                            weightNegative = true;
                        }
                        int decimalPlaces = field.path("decimalPlaces").asInt(0);
                        weight = new BigDecimal(weightStr.startsWith("+") || weightStr.startsWith("-")
                                ? weightStr.substring(1) : weightStr);
                        if (decimalPlaces > 0) {
                            weight = weight.movePointLeft(decimalPlaces);
                        }
                        break;
                    case "unit":
                        unit = value.trim();
                        if (unit.isEmpty()) unit = "kg";
                        break;
                    case "stable":
                        JsonNode mapping = field.path("mapping");
                        String flag = value.trim();
                        if (!mapping.isObject() || !mapping.has(flag)) {
                            return ScaleDataParseResult.error("INVALID_STABLE", "稳定标志不认识: '" + flag + "'");
                        }
                        stable = mapping.path(flag).asBoolean();
                        break;
                    default:
                        break;
                }
            }

            // 独立符号位和重量域各带一个符号 ("-" + "-00520"): 到底是不是负数说不清, 整帧拒绝, 不取反一次蒙一个
            if (signFieldSeen && weightCarriesSign) {
                return ScaleDataParseResult.error("DOUBLE_SIGN", "符号位和重量域各带一个符号, 无法判断正负");
            }
            // 字段之后只允许空白 / 换行 / 配置声明的结束标记; 其它多余字节说明帧格式对不上 (或两帧粘在一起)
            String trailing = ascii.substring(fieldsEnd);
            for (char c : trailing.toCharArray()) {
                if (c != ' ' && c != '\r' && c != '\n' && endMarker.indexOf(c) < 0) {
                    return ScaleDataParseResult.error("FRAME_TRAILING", "字段之后有多余内容: '" + trailing.trim() + "'");
                }
            }

            // 应用符号
            if (weight != null && (sign == '-' || weightNegative)) {
                weight = weight.negate();
            }

            return ScaleDataParseResult.builder()
                    .success(true)
                    .weight(weight)
                    .unit(unit)
                    .stable(stable)
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (NumberFormatException e) {
            return ScaleDataParseResult.error("INVALID_WEIGHT", "无法解析重量值: " + e.getMessage());
        } catch (Exception e) {
            return ScaleDataParseResult.error("ASCII_PARSE_ERROR", e.getMessage());
        }
    }

    /** 帧起止标记: 种子里写成字面 \r\n 四个字符 (SQL 转义), 也可能是真的 CR LF; STX/ETX 用名字。 */
    private static String frameMarker(JsonNode format, String key) {
        JsonNode n = format.path(key);
        if (!n.isTextual()) {
            return "";
        }
        return n.asText()
                .replace("\\r", "\r").replace("\\n", "\n")
                .replace("STX", "\u0002").replace("ETX", "\u0003");
    }

    /**
     * 解析 ASCII 变长帧
     */
    private ScaleDataParseResult parseAsciiVariableFrame(JsonNode format, byte[] rawData) {
        try {
            String ascii = new String(rawData, StandardCharsets.US_ASCII);
            String delimiter = format.path("delimiter").asText(",");
            String[] parts = ascii.split(delimiter);

            JsonNode fields = format.path("fields");
            BigDecimal weight = null;
            String unit = "kg";
            Boolean stable = null;

            for (JsonNode field : fields) {
                String name = field.path("name").asText();
                int index = field.path("index").asInt(-1);

                if (index >= 0 && index < parts.length) {
                    String value = parts[index].trim();

                    switch (name) {
                        case "weight":
                            if (!value.matches("[+-]?\\d+(\\.\\d+)?")) {
                                return ScaleDataParseResult.error("INVALID_WEIGHT", "无法解析重量值: '" + value + "'");
                            }
                            weight = new BigDecimal(value);
                            break;
                        case "unit":
                            unit = value;
                            break;
                        case "stable":
                            stable = "S".equalsIgnoreCase(value) || "1".equals(value) || "true".equalsIgnoreCase(value);
                            break;
                    }
                }
            }

            return ScaleDataParseResult.builder()
                    .success(true)
                    .weight(weight)
                    .unit(unit)
                    .stable(stable)
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            return ScaleDataParseResult.error("ASCII_VARIABLE_PARSE_ERROR", e.getMessage());
        }
    }

    /**
     * 解析 HEX 定长帧
     */
    private ScaleDataParseResult parseHexFixedFrame(JsonNode format, byte[] rawData) {
        try {
            JsonNode fields = format.path("fields");
            BigDecimal weight = null;
            String unit = "kg";
            Boolean stable = null;

            for (JsonNode field : fields) {
                String name = field.path("name").asText();
                int start = field.path("start").asInt();
                int length = field.path("length").asInt();

                if (start + length > rawData.length) {
                    return ScaleDataParseResult.error("FRAME_TOO_SHORT", "数据帧长度不足");
                }

                byte[] fieldBytes = Arrays.copyOfRange(rawData, start, start + length);

                switch (name) {
                    case "weight":
                        // 大端序解析
                        long rawValue = 0;
                        for (byte b : fieldBytes) {
                            rawValue = (rawValue << 8) | (b & 0xFF);
                        }
                        int decimalPlaces = field.path("decimalPlaces").asInt(0);
                        weight = BigDecimal.valueOf(rawValue).movePointLeft(decimalPlaces);
                        break;
                    case "unit":
                        unit = new String(fieldBytes, StandardCharsets.US_ASCII).trim();
                        break;
                    case "stable":
                        stable = (fieldBytes[0] & 0x01) == 1;
                        break;
                }
            }

            return ScaleDataParseResult.builder()
                    .success(true)
                    .weight(weight)
                    .unit(unit)
                    .stable(stable)
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            return ScaleDataParseResult.error("HEX_PARSE_ERROR", e.getMessage());
        }
    }

    /**
     * 解析 Modbus RTU 帧
     */
    private ScaleDataParseResult parseModbusRtuFrame(JsonNode format, byte[] rawData) {
        try {
            // Modbus RTU 帧结构: [从站地址][功能码][数据...][CRC16]
            if (rawData.length < 5) {
                return ScaleDataParseResult.error("MODBUS_FRAME_TOO_SHORT", "Modbus帧长度不足");
            }

            int functionCode = rawData[1] & 0xFF;

            // 验证功能码 (通常是03读取保持寄存器); 0x83 等异常应答也在这里被拒绝
            if (functionCode != 3) {
                return ScaleDataParseResult.error("UNSUPPORTED_FUNCTION_CODE", "不支持的功能码: " + functionCode);
            }

            int byteCount = rawData[2] & 0xFF;
            if (rawData.length != 3 + byteCount + 2) {
                return ScaleDataParseResult.error("MODBUS_DATA_INCOMPLETE", "Modbus帧长度与字节数不符");
            }

            // CRC16 (低字节在前), 不通过就是线上被干扰或半帧拼接, 整帧丢弃
            int crcCalc = modbusCrc16(rawData, rawData.length - 2);
            int crcFrame = (rawData[rawData.length - 2] & 0xFF) | ((rawData[rawData.length - 1] & 0xFF) << 8);
            if (crcCalc != crcFrame) {
                return ScaleDataParseResult.error("CRC_MISMATCH", "Modbus CRC 校验失败");
            }

            // 解析寄存器数据 (大端序, 有符号 32 位: 去皮后的负重不能读成 42949667.76)
            int weightOffset = format.path("weightRegisterOffset").asInt(0);
            int decimalPlaces = format.path("decimalPlaces").asInt(2);
            if (byteCount < weightOffset + 4) {
                return ScaleDataParseResult.error("MODBUS_DATA_INCOMPLETE", "Modbus数据不够 4 字节重量");
            }

            int rawValue = 0;
            for (int i = 0; i < 4; i++) {
                rawValue = (rawValue << 8) | (rawData[3 + weightOffset + i] & 0xFF);
            }

            BigDecimal weight = BigDecimal.valueOf(rawValue).movePointLeft(decimalPlaces);

            return ScaleDataParseResult.builder()
                    .success(true)
                    .weight(weight)
                    .unit("kg")
                    .stable(true) // Modbus通常不传稳定状态，默认稳定
                    .timestamp(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            return ScaleDataParseResult.error("MODBUS_PARSE_ERROR", e.getMessage());
        }
    }

    private static int modbusCrc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc;
    }

    // ==================== 协议自动识别 ====================

    @Override
    public List<ProtocolMatchResult> autoDetectProtocol(byte[] sampleData) {
        List<ProtocolMatchResult> results = new ArrayList<>();
        List<ScaleProtocolConfig> activeProtocols = protocolRepository.findByIsActiveTrue();

        for (ScaleProtocolConfig protocol : activeProtocols) {
            ProtocolMatchResult match = tryMatchProtocol(protocol, sampleData);
            if (match != null && match.getConfidence() > 0) {
                results.add(match);
            }
        }

        // 按置信度降序排序
        results.sort((a, b) -> b.getConfidence().compareTo(a.getConfidence()));

        return results;
    }

    @Override
    public List<ProtocolMatchResult> autoDetectProtocolHex(String sampleDataHex) {
        return autoDetectProtocol(hexToBytes(sampleDataHex));
    }

    /**
     * 尝试匹配协议
     */
    private ProtocolMatchResult tryMatchProtocol(ScaleProtocolConfig protocol, byte[] sampleData) {
        try {
            ScaleDataParseResult parseResult = parseWithConfig(protocol, sampleData);

            if (parseResult.isSuccess()) {
                // 解析成功，根据结果的合理性评估置信度
                int confidence = evaluateParseResultConfidence(parseResult);

                return ProtocolMatchResult.builder()
                        .protocolId(protocol.getId())
                        .protocolCode(protocol.getProtocolCode())
                        .protocolName(protocol.getProtocolName())
                        .confidence(confidence)
                        .exactMatch(confidence >= 90)
                        .matchReason(confidence >= 90 ? "数据帧完全匹配，解析结果合理" : "部分匹配")
                        .testParseResult(parseResult)
                        .build();
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 评估解析结果的置信度
     */
    private int evaluateParseResultConfidence(ScaleDataParseResult result) {
        int confidence = 50; // 基础分

        // 重量值合理性检查 (0-100000kg)
        if (result.getWeight() != null) {
            BigDecimal weight = result.getWeight().abs();
            if (weight.compareTo(BigDecimal.ZERO) >= 0 && weight.compareTo(new BigDecimal("100000")) <= 0) {
                confidence += 20;
            }
        }

        // 单位有效性检查
        if (result.getUnit() != null &&
            (result.getUnit().equals("kg") || result.getUnit().equals("g") ||
             result.getUnit().equals("lb") || result.getUnit().equals("oz"))) {
            confidence += 15;
        }

        // 稳定状态存在
        if (result.getStable() != null) {
            confidence += 15;
        }

        return Math.min(confidence, 100);
    }

    // ==================== 测试验证 ====================

    @Override
    @Transactional
    public TestCaseResult runTestCase(String testCaseId) {
        Optional<ScaleProtocolTestCase> testCaseOpt = testCaseRepository.findById(testCaseId);
        if (testCaseOpt.isEmpty()) {
            return TestCaseResult.error(testCaseId, "Unknown", "测试用例不存在");
        }

        ScaleProtocolTestCase testCase = testCaseOpt.get();
        long startTime = System.currentTimeMillis();

        try {
            // 获取输入数据
            String inputHex = testCase.getInputDataHex();
            if (inputHex == null || inputHex.isEmpty()) {
                inputHex = asciiToHex(testCase.getInputDataAscii());
            }

            // 执行解析
            ScaleDataParseResult parseResult = parseScaleDataHex(testCase.getProtocolId(), inputHex);

            TestCaseResult result;

            if (testCase.getIsNegativeTest()) {
                // 负面测试: 期望解析失败
                if (!parseResult.isSuccess()) {
                    result = TestCaseResult.builder()
                            .testCaseId(testCaseId)
                            .testName(testCase.getTestName())
                            .passed(true)
                            .resultStatus("PASSED")
                            .isNegativeTest(true)
                            .expectedErrorCode(testCase.getExpectedErrorCode())
                            .actualErrorCode(parseResult.getErrorCode())
                            .executedAt(LocalDateTime.now())
                            .executionTimeMs(System.currentTimeMillis() - startTime)
                            .build();
                } else {
                    result = TestCaseResult.failed(testCaseId, testCase.getTestName(),
                            "负面测试失败：预期解析失败但实际解析成功");
                }
            } else {
                // 正面测试: 期望解析成功并匹配预期值
                if (parseResult.isSuccess()) {
                    boolean weightMatch = compareWeights(parseResult.getWeight(), testCase.getExpectedWeight());
                    boolean unitMatch = testCase.getExpectedUnit() == null ||
                                        testCase.getExpectedUnit().equals(parseResult.getUnit());
                    boolean stableMatch = testCase.getExpectedStable() == null ||
                                          testCase.getExpectedStable().equals(parseResult.getStable());

                    if (weightMatch && unitMatch && stableMatch) {
                        result = TestCaseResult.passed(testCaseId, testCase.getTestName(),
                                parseResult, testCase.getExpectedWeight());
                    } else {
                        StringBuilder reason = new StringBuilder("值不匹配: ");
                        if (!weightMatch) reason.append("重量(期望:").append(testCase.getExpectedWeight())
                                .append(",实际:").append(parseResult.getWeight()).append(") ");
                        if (!unitMatch) reason.append("单位(期望:").append(testCase.getExpectedUnit())
                                .append(",实际:").append(parseResult.getUnit()).append(") ");
                        if (!stableMatch) reason.append("稳定(期望:").append(testCase.getExpectedStable())
                                .append(",实际:").append(parseResult.getStable()).append(") ");
                        result = TestCaseResult.failed(testCaseId, testCase.getTestName(), reason.toString());
                    }
                } else {
                    result = TestCaseResult.failed(testCaseId, testCase.getTestName(),
                            "解析失败: " + parseResult.getErrorMessage());
                }
            }

            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

            // 更新测试用例记录
            testCase.setLastRunResult(ScaleProtocolTestCase.TestResult.valueOf(result.getResultStatus()));
            testCase.setLastRunAt(LocalDateTime.now());
            testCase.setLastRunError(result.getErrorMessage());
            testCaseRepository.save(testCase);

            return result;

        } catch (Exception e) {
            log.error("Test case execution error: {}", testCaseId, e);
            return TestCaseResult.error(testCaseId, testCase.getTestName(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public List<TestCaseResult> runAllTestCases(String protocolId) {
        List<ScaleProtocolTestCase> testCases = testCaseRepository.findByProtocolIdAndIsActiveTrueOrderByPriorityAsc(protocolId);
        List<TestCaseResult> results = new ArrayList<>();

        for (ScaleProtocolTestCase testCase : testCases) {
            results.add(runTestCase(testCase.getId()));
        }

        return results;
    }

    @Override
    public List<ScaleProtocolTestCase> getTestCases(String protocolId) {
        return testCaseRepository.findByProtocolIdOrderByPriorityAsc(protocolId);
    }

    // ==================== 工具方法 ====================

    @Override
    public String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @Override
    public byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        hex = hex.replaceAll("\\s", "").toUpperCase();
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    public boolean validateProtocolConfig(String protocolId) {
        Optional<ScaleProtocolConfig> configOpt = protocolRepository.findById(protocolId);
        if (configOpt.isEmpty()) return false;

        ScaleProtocolConfig config = configOpt.get();

        // 验证帧格式有效性
        try {
            if (config.getFrameFormat() == null) return false;
            JsonNode formatNode = objectMapper.readTree(config.getFrameFormat());
            return formatNode.has("frameType") && formatNode.has("fields");
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 私有辅助方法 ====================

    private String asciiToHex(String ascii) {
        if (ascii == null) return "";
        return bytesToHex(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean compareWeights(BigDecimal actual, BigDecimal expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        // 允许0.01的误差
        return actual.subtract(expected).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }
}
