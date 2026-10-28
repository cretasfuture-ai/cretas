package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.view.SavedViewDtos.EntityOption;
import com.cretas.aims.dto.view.SavedViewDtos.ExecuteResult;
import com.cretas.aims.dto.view.SavedViewDtos.SaveRequest;
import com.cretas.aims.dto.view.SavedViewDtos.ViewSummary;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.view.SavedViewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * S6-01 保存的动态业务视图。身份只取 JWT 建立的 {@link AuthenticatedActor}; 权限按实体 / 字段在服务层逐次判定
 * (同一个视图不同的人打开看到的行列可以不同), 所以这里不挂统一的 @RequirePermission。
 * 平台管理员没有工厂用户身份, 不能保存或打开视图。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/saved-views")
public class SavedViewController {

    private final SavedViewService service;

    public SavedViewController(SavedViewService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public ApiResponse<List<EntityOption>> options(@PathVariable String factoryId, HttpServletRequest request) {
        return ApiResponse.success(service.options(factoryId, actorId(factoryId, request)));
    }

    @GetMapping
    public ApiResponse<List<ViewSummary>> list(@PathVariable String factoryId, HttpServletRequest request) {
        return ApiResponse.success(service.list(factoryId, actorId(factoryId, request)));
    }

    @PostMapping
    public ApiResponse<ViewSummary> create(@PathVariable String factoryId, @RequestBody SaveRequest body,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(factoryId, actorId(factoryId, request), body));
    }

    @GetMapping("/{id}")
    public ApiResponse<ViewSummary> get(@PathVariable String factoryId, @PathVariable String id,
                                        HttpServletRequest request) {
        return ApiResponse.success(service.get(factoryId, actorId(factoryId, request), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ViewSummary> update(@PathVariable String factoryId, @PathVariable String id,
                                           @RequestBody SaveRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.update(factoryId, actorId(factoryId, request), id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String factoryId, @PathVariable String id,
                                    HttpServletRequest request) {
        service.delete(factoryId, actorId(factoryId, request), id);
        return ApiResponse.success(null);
    }

    /** 每次打开都现算; 结果不缓存。 */
    @GetMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<ExecuteResult>> execute(@PathVariable String factoryId,
                                                              @PathVariable String id,
                                                              HttpServletRequest request) {
        ExecuteResult result = service.execute(factoryId, actorId(factoryId, request), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(result));
    }

    private static Long actorId(String pathFactoryId, HttpServletRequest request) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户未登录"));
        if (actor.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "平台账号不能使用工厂业务视图");
        }
        if (!pathFactoryId.equals(actor.factoryId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "工厂范围不匹配");
        }
        return actor.id();
    }
}
