package com.cretas.aims.service.view;

import com.cretas.aims.dto.view.SavedViewDtos.EntityOption;
import com.cretas.aims.dto.view.SavedViewDtos.ExecuteResult;
import com.cretas.aims.dto.view.SavedViewDtos.FieldOption;
import com.cretas.aims.dto.view.SavedViewDtos.SaveRequest;
import com.cretas.aims.dto.view.SavedViewDtos.ViewSummary;
import com.cretas.aims.entity.SavedView;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.SavedViewRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S6-01 保存的动态业务视图。保存的是可重算的查询定义, 每次打开都按「当前用户」的权限与本厂范围现算:
 * 无该实体读权限 → 403; 缺字段权限 → 该列不返回 (并列在 hiddenColumns); 过滤 / 排序用到无权字段 → 403
 * (不静默丢掉条件, 否则会比定义多放出行)。别厂的视图一律 404。
 */
@Service
public class SavedViewService {

    private final SavedViewRepository repository;
    private final ViewEntityCatalog catalog;
    private final ViewDefinitionValidator validator;
    private final ViewQueryEngine engine;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ObjectMapper strictMapper;

    public SavedViewService(SavedViewRepository repository, ViewEntityCatalog catalog,
                            ViewDefinitionValidator validator, ViewQueryEngine engine,
                            UserRepository userRepository, PermissionService permissionService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.validator = validator;
        this.engine = engine;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.strictMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /** 可建视图的实体与字段, 带当前用户是否有权 (前端据此只展示可用项)。 */
    public List<EntityOption> options(String factoryId, Long userId) {
        User user = requireUser(factoryId, userId);
        List<EntityOption> out = new ArrayList<>();
        for (ViewEntityCatalog.EntitySpec spec : catalog.all()) {
            boolean entityOk = permissionService.hasAnyPermission(user, spec.readAnyPermission().toArray(new String[0]));
            List<FieldOption> fields = new ArrayList<>();
            for (ViewEntityCatalog.FieldSpec fs : spec.fields().values()) {
                boolean ok = entityOk && fieldAllowed(user, fs);
                fields.add(new FieldOption(fs.name(), fs.label(), fs.type().name().toLowerCase(), ok,
                        opsOf(fs.type())));
            }
            out.add(new EntityOption(spec.key(), spec.label(), entityOk, fields));
        }
        return out;
    }

    @Transactional
    public ViewSummary create(String factoryId, Long userId, SaveRequest req) {
        User user = requireUser(factoryId, userId);
        String name = requireName(req);
        ViewDefinition def = parse(req.definition());
        ViewEntityCatalog.EntitySpec spec = validator.validate(def);
        requireEntityRead(user, spec);

        SavedView v = new SavedView();
        v.setId(UUID.randomUUID().toString());
        v.setFactoryId(factoryId);
        v.setOwnerUserId(userId);
        v.setName(name);
        v.setEntityKey(spec.key());
        v.setDefinitionJson(write(def));
        v.setVersion(1);
        v.setShared(Boolean.TRUE.equals(req.shared()));
        return toSummary(repository.save(v), userId);
    }

    @Transactional
    public ViewSummary update(String factoryId, Long userId, String id, SaveRequest req) {
        User user = requireUser(factoryId, userId);
        SavedView v = requireVisible(factoryId, userId, id);
        if (!v.getOwnerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有视图创建人可以修改");
        }
        if (req.version() != null && !req.version().equals(v.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视图已被修改, 请刷新后重试");
        }
        if (req.name() != null) v.setName(requireName(req));
        if (req.shared() != null) v.setShared(req.shared());
        if (req.definition() != null) {
            ViewDefinition def = parse(req.definition());
            ViewEntityCatalog.EntitySpec spec = validator.validate(def);
            requireEntityRead(user, spec);
            String json = write(def);
            if (!json.equals(v.getDefinitionJson())) {
                v.setDefinitionJson(json);
                v.setEntityKey(spec.key());
                v.setVersion(v.getVersion() + 1);
            }
        }
        return toSummary(repository.save(v), userId);
    }

    @Transactional
    public void delete(String factoryId, Long userId, String id) {
        SavedView v = requireVisible(factoryId, userId, id);
        if (!v.getOwnerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有视图创建人可以删除");
        }
        v.setDeletedAt(LocalDateTime.now());
        repository.save(v);
    }

    @Transactional(readOnly = true)
    public List<ViewSummary> list(String factoryId, Long userId) {
        requireUser(factoryId, userId);
        List<ViewSummary> out = new ArrayList<>();
        for (SavedView v : repository.findVisible(factoryId, userId)) out.add(toSummary(v, userId));
        return out;
    }

    @Transactional(readOnly = true)
    public ViewSummary get(String factoryId, Long userId, String id) {
        requireUser(factoryId, userId);
        return toSummary(requireVisible(factoryId, userId, id), userId);
    }

    /** 按当前用户权限重算。结果行不落库、不缓存。 */
    @Transactional(readOnly = true)
    public ExecuteResult execute(String factoryId, Long userId, String id) {
        User user = requireUser(factoryId, userId);
        SavedView v = requireVisible(factoryId, userId, id);
        ViewDefinition def = read(v.getDefinitionJson());
        ViewEntityCatalog.EntitySpec spec = validator.validate(def);
        requireEntityRead(user, spec);

        List<String> visible = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (String col : def.getColumns()) {
            (fieldAllowed(user, spec.fields().get(col)) ? visible : hidden).add(col);
        }
        if (visible.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您无权查看此视图中的任何列");
        }
        for (ViewDefinition.Filter f : def.getFilters()) {
            if (!fieldAllowed(user, spec.fields().get(f.getField()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "此视图按您无权查看的字段过滤: " + f.getField());
            }
        }
        ViewDefinition.Sort sort = def.getSort();
        if (sort != null && !fieldAllowed(user, spec.fields().get(sort.getField()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此视图按您无权查看的字段排序: " + sort.getField());
        }

        int limit = def.getLimit() == null ? ViewDefinitionValidator.DEFAULT_LIMIT : def.getLimit();
        List<Map<String, Object>> rows = engine.run(spec, factoryId, visible, def.getFilters(), sort, limit + 1);
        boolean truncated = rows.size() > limit;
        if (truncated) rows = new ArrayList<>(rows.subList(0, limit));
        return new ExecuteResult(v.getId(), v.getName(), v.getVersion(), spec.key(), visible, hidden, rows,
                rows.size(), truncated, LocalDateTime.now());
    }

    // ---------------------------------------------------------------- helpers

    private User requireUser(String factoryId, Long userId) {
        User user = userId == null ? null : userRepository.findByIdAndFactoryId(userId, factoryId).orElse(null);
        if (user == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不属于当前工厂");
        return user;
    }

    private void requireEntityRead(User user, ViewEntityCatalog.EntitySpec spec) {
        if (!permissionService.hasAnyPermission(user, spec.readAnyPermission().toArray(new String[0]))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您无权查看「" + spec.label() + "」数据");
        }
    }

    private boolean fieldAllowed(User user, ViewEntityCatalog.FieldSpec fs) {
        return !fs.restricted() || permissionService.hasAnyPermission(user, fs.anyPermission().toArray(new String[0]));
    }

    /** 别厂的 / 不存在的 / 别人未共享的一律 404, 不暴露存在性。 */
    private SavedView requireVisible(String factoryId, Long userId, String id) {
        SavedView v = repository.findByIdAndFactoryId(id, factoryId).orElse(null);
        if (v == null || !(v.getOwnerUserId().equals(userId) || Boolean.TRUE.equals(v.getShared()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视图不存在");
        }
        return v;
    }

    private static String requireName(SaveRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视图名称必填, 且不超过 100 字");
        }
        return name;
    }

    private ViewDefinition parse(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视图定义不能为空");
        }
        try {
            return strictMapper.treeToValue(node, ViewDefinition.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视图定义格式不对");
        }
    }

    private ViewDefinition read(String json) {
        try {
            return strictMapper.readValue(json, ViewDefinition.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "已保存的视图定义无法解析");
        }
    }

    private String write(ViewDefinition def) {
        try {
            return strictMapper.writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ViewSummary toSummary(SavedView v, Long userId) {
        JsonNode def;
        try {
            def = strictMapper.readTree(v.getDefinitionJson());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "已保存的视图定义无法解析");
        }
        return new ViewSummary(v.getId(), v.getName(), v.getEntityKey(), v.getVersion(),
                Boolean.TRUE.equals(v.getShared()), v.getOwnerUserId().equals(userId), v.getOwnerUserId(), def,
                v.getUpdatedAt());
    }

    private static List<String> opsOf(ViewEntityCatalog.FieldType t) {
        Map<ViewEntityCatalog.FieldType, List<String>> m = new LinkedHashMap<>();
        m.put(ViewEntityCatalog.FieldType.STRING, List.of("eq", "ne", "contains"));
        m.put(ViewEntityCatalog.FieldType.NUMBER, List.of("eq", "ne", "gt", "gte", "lt", "lte"));
        m.put(ViewEntityCatalog.FieldType.BOOLEAN, List.of("eq"));
        return m.get(t);
    }
}
