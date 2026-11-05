package com.cretas.aims.service.material;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.processsecret.ProcessSecretWorkflowOutputs;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Authorization for the merge path's automatic draft-workflow rewrite.
 *
 * <p>The material controller's warehouse permission is intentionally not enough for this
 * side effect: changing a draft graph is the same protected production write as the normal
 * Workflow controller.  This guard is called only after the merge's final plan has locked
 * every affected workflow, and performs no locks of its own.</p>
 */
@Service
public class MaterialMergeWorkflowWriteGuard {

    private static final String WORKFLOW_MODULE = "production_plan";

    private static final Set<FactoryUserRole> WORKFLOW_WRITE_ROLES = EnumSet.of(
            FactoryUserRole.factory_super_admin,
            FactoryUserRole.workshop_supervisor,
            FactoryUserRole.department_admin);

    private final ProductProcessWorkflowRepository workflowRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final FactoryConfigService factoryConfigService;
    private final UserModuleAccessService userModuleAccessService;
    private final ProcessSecretRecipeGuard processSecretGuard;

    public MaterialMergeWorkflowWriteGuard(ProductProcessWorkflowRepository workflowRepository,
                                           PlatformAdminRepository platformAdminRepository,
                                           UserRepository userRepository,
                                           PermissionService permissionService,
                                           FactoryConfigService factoryConfigService,
                                           UserModuleAccessService userModuleAccessService,
                                           ProcessSecretRecipeGuard processSecretGuard) {
        this.workflowRepository = workflowRepository;
        this.platformAdminRepository = platformAdminRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.factoryConfigService = factoryConfigService;
        this.userModuleAccessService = userModuleAccessService;
        this.processSecretGuard = processSecretGuard;
    }

    /**
     * Authorize every draft workflow in the final merge plan before any reference is written.
     * An empty draft set is deliberately a no-op; completed/history references do not grant
     * this merge path a production write capability.
     */
    public void require(String factoryId, MaterialMergeReferenceReplacer.Plan finalPlan) {
        if (finalPlan == null || finalPlan.draftWorkflows().isEmpty()) return;

        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution()
                .orElseThrow(() -> forbidden("无法确认当前工艺写入身份"));
        requireProductionWorkflowWrite(factoryId, actor);

        finalPlan.draftWorkflows().keySet().stream().sorted().forEach(workflowId -> {
            ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                    .orElseThrow(() -> conflict("受影响工艺图已不存在，请刷新后重试"));
            requireWorkflowProducts(factoryId, workflow);
        });
    }

    private void requireProductionWorkflowWrite(String factoryId, AuthenticatedActor actor) {
        if (!factoryConfigService.isModuleEnabled(factoryId, WORKFLOW_MODULE)) {
            throw forbidden("生产计划模块未启用，不能自动修改工艺草稿");
        }
        if (actor.type() == AuthenticatedActorType.PLATFORM_ADMIN) {
            if (!actor.hasFactoryBypass() || PlatformFactoryViewReadPolicy.isCurrentView()) {
                throw forbidden("当前平台身份只能读取工厂数据，不能自动修改工艺草稿");
            }
            PlatformAdmin admin = platformAdminRepository.findById(actor.id())
                    .filter(PlatformAdmin::isActive)
                    .orElseThrow(() -> forbidden("当前平台账号已失效或不存在"));
            PlatformRole role = admin.getPlatformRole();
            if (role == null || actor.role() == null || !role.name().equalsIgnoreCase(actor.role())) {
                throw forbidden("当前平台角色已变化，请重新登录后重试");
            }
            return;
        }

        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            throw forbidden("当前身份类型不能修改工艺草稿");
        }
        if (actor.factoryId() == null || !factoryId.equals(actor.factoryId())) {
            throw forbidden("当前账号不属于该工厂，不能修改工艺草稿");
        }
        User user = userRepository.findByIdAndFactoryId(actor.id(), factoryId)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> forbidden("当前账号已失效或不属于该工厂"));
        FactoryUserRole role = user.getRoleEnum();
        if (actor.role() == null || role == FactoryUserRole.unactivated
                || !role.name().equalsIgnoreCase(actor.role())
                || !WORKFLOW_WRITE_ROLES.contains(role)
                || !permissionService.hasPermission(user, "production:read_write")) {
            throw forbidden("当前账号没有工艺草稿的生产读写权限");
        }
        if (!userModuleAccessService.canAccessModule(user, WORKFLOW_MODULE)) {
            throw forbidden("当前账号没有生产计划模块访问权限");
        }
    }

    private void requireWorkflowProducts(String factoryId, ProductProcessWorkflow workflow) {
        String resourceRef = "material merge workflow " + workflow.getId();
        processSecretGuard.requireProduct(factoryId, workflow.getProductTypeId(),
                ProcessSecretAction.EDIT, resourceRef + " owner");
        java.util.Optional<Set<String>> products = ProcessSecretWorkflowOutputs
                .productsInGraph(workflow.getNodesJson());
        if (products.isPresent()) {
            processSecretGuard.requireProtectedAmong(factoryId, products.get(),
                    ProcessSecretAction.EDIT, resourceRef + " outputs");
        } else {
            processSecretGuard.requireAllProtected(factoryId,
                    ProcessSecretAction.EDIT, resourceRef + " outputs parse failure");
        }
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(403, message)
                .withCode("WORKFLOW_WRITE_FORBIDDEN")
                .withHint("请使用有生产读写权限且具备工艺编辑授权的账号重试")
                .withSeverity("error");
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, message)
                .withCode("MATERIAL_MERGE_CONFLICT")
                .withHint("请刷新受影响的工艺图后重试")
                .withSeverity("warning");
    }
}
