package com.yanyue.rag.api.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.evaluation.EvaluationDatasetBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/evaluation/query-files")
public class EvaluationQueryFileController {
    private static final List<String> COLUMNS = List.of(
            "query", "case_id", "expected_answer", "expected_document_keys",
            "expect_no_answer", "category", "tags", "metadata_json");

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvaluationQueryFileController(DSLContext dsl, ObjectMapper objectMapper, Clock clock) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping(path = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QueryFilePreview parse(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "knowledgeBaseId") List<UUID> knowledgeBaseIds
    ) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Query workbook is empty");
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("At least one knowledge base is required");
        }
        var documentKeys = documentKeys(user.organizationId(), knowledgeBaseIds);
        var cases = new ArrayList<EvaluationDatasetBundle.CaseEntry>();
        var rows = new ArrayList<QueryRowPreview>();
        var globalErrors = new ArrayList<String>();
        var suggestedName = fileName(file.getOriginalFilename()) + " · "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(clock.instant());

        try (var workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Query workbook has no sheet");
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw new IllegalArgumentException("Query workbook has no header row");
            var formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE, true);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            var columns = new HashMap<String, Integer>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                var name = formatter.formatCellValue(header.getCell(index), evaluator).strip().toLowerCase(Locale.ROOT);
                if (!name.isBlank()) columns.put(name, index);
            }
            if (!columns.containsKey("query")) globalErrors.add("缺少必填列 query");
            var unknown = columns.keySet().stream().filter(key -> !COLUMNS.contains(key)).sorted().toList();
            if (!unknown.isEmpty()) globalErrors.add("存在未知列: " + String.join(", ", unknown));

            for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                var source = sheet.getRow(index);
                if (source == null) continue;
                var question = value(source, columns, "query", formatter, evaluator);
                if (question.isBlank() && COLUMNS.stream().allMatch(column -> value(source, columns, column, formatter, evaluator).isBlank())) continue;
                var errors = new ArrayList<String>();
                if (question.isBlank()) errors.add("query 不能为空");
                var expectedAnswer = blankToNull(value(source, columns, "expected_answer", formatter, evaluator));
                var expectedKeys = split(value(source, columns, "expected_document_keys", formatter, evaluator));
                var expectedDocumentIds = new ArrayList<UUID>();
                for (var key : expectedKeys) {
                    var matches = documentKeys.getOrDefault(key.toLowerCase(Locale.ROOT), List.of());
                    if (matches.isEmpty()) errors.add("找不到文档标识 " + key);
                    else if (matches.size() > 1) errors.add("文档标识不唯一 " + key);
                    else expectedDocumentIds.add(matches.getFirst());
                }
                var expectNoAnswer = booleanValue(value(source, columns, "expect_no_answer", formatter, evaluator));
                if (expectedAnswer == null && expectedKeys.isEmpty() && !expectNoAnswer) {
                    errors.add("至少提供标准答案、预期文档或 expect_no_answer=true");
                }
                var metadata = new LinkedHashMap<String, Object>();
                put(metadata, "caseId", value(source, columns, "case_id", formatter, evaluator));
                put(metadata, "category", value(source, columns, "category", formatter, evaluator));
                var tags = split(value(source, columns, "tags", formatter, evaluator));
                if (!tags.isEmpty()) metadata.put("tags", tags);
                if (expectNoAnswer) metadata.put("expectNoAnswer", true);
                var metadataJson = value(source, columns, "metadata_json", formatter, evaluator);
                if (!metadataJson.isBlank()) {
                    try {
                        metadata.putAll(objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() { }));
                    } catch (JsonProcessingException exception) {
                        errors.add("metadata_json 不是有效 JSON 对象");
                    }
                }
                rows.add(new QueryRowPreview(index + 1, question, expectedAnswer != null,
                        expectedDocumentIds.size(), expectNoAnswer, List.copyOf(errors)));
                if (errors.isEmpty()) {
                    cases.add(new EvaluationDatasetBundle.CaseEntry(
                            question, expectedAnswer, List.copyOf(expectedDocumentIds), Map.copyOf(metadata)));
                }
            }
        }
        if (rows.isEmpty()) globalErrors.add("没有可评测的数据行");
        var bundle = new EvaluationDatasetBundle(
                EvaluationDatasetBundle.SCHEMA_VERSION, null, null, suggestedName,
                "由 Query XLSX 导入", List.copyOf(cases));
        return new QueryFilePreview(suggestedName, List.copyOf(rows), List.copyOf(globalErrors), bundle);
    }

    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template() throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("queries");
            var header = sheet.createRow(0);
            for (int index = 0; index < COLUMNS.size(); index++) {
                header.createCell(index).setCellValue(COLUMNS.get(index));
                sheet.setColumnWidth(index, (index == 0 || index == 2 ? 42 : 24) * 256);
            }
            var example = sheet.createRow(1);
            example.createCell(0).setCellValue("示例问题：制度生效时间是什么？");
            example.createCell(1).setCellValue("CASE-001");
            example.createCell(2).setCellValue("示例标准答案");
            example.createCell(3).setCellValue("DOC-2026-001");
            example.createCell(4).setCellValue("false");
            example.createCell(5).setCellValue("事实问答");
            example.createCell(6).setCellValue("时间;制度");
            example.createCell(7).setCellValue("{}");
            workbook.write(output);
            var headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment().filename("evaluation-query-template.xlsx").build());
            return ResponseEntity.ok().headers(headers).body(output.toByteArray());
        }
    }

    private Map<String, List<UUID>> documentKeys(UUID organizationId, List<UUID> knowledgeBaseIds) {
        var result = new LinkedHashMap<String, List<UUID>>();
        var records = dsl.fetch("""
                SELECT d.id, dv.metadata ->> 'document_key' AS document_key
                FROM document d
                JOIN document_version dv ON dv.id = d.current_version_id
                WHERE d.organization_id = ? AND d.knowledge_base_id = ANY(?::uuid[])
                  AND d.status = 'ACTIVE' AND dv.status = 'PUBLISHED'
                  AND dv.metadata ? 'document_key'
                """, organizationId, knowledgeBaseIds.toArray(UUID[]::new));
        for (var record : records) {
            var key = record.get("document_key", String.class);
            if (key == null || key.isBlank()) continue;
            result.computeIfAbsent(key.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(record.get("id", UUID.class));
        }
        return result;
    }

    private String value(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns, String name,
                         DataFormatter formatter, org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        var index = columns.get(name);
        return index == null ? "" : formatter.formatCellValue(row.getCell(index), evaluator).strip();
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("[,，;；]\\s*")).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    private boolean booleanValue(String value) {
        return List.of("true", "1", "是", "yes").contains(value.toLowerCase(Locale.ROOT));
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String fileName(String original) {
        if (original == null || original.isBlank()) return "评测任务";
        var normalized = original.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        var dot = normalized.lastIndexOf('.');
        return dot > 0 ? normalized.substring(0, dot) : normalized;
    }

    public record QueryFilePreview(String suggestedName, List<QueryRowPreview> rows,
                                   List<String> errors, EvaluationDatasetBundle bundle) { }

    public record QueryRowPreview(int rowNumber, String question, boolean hasExpectedAnswer,
                                  int expectedDocumentCount, boolean expectNoAnswer,
                                  List<String> errors) { }
}
