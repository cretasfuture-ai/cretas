package com.cretas.aims.blueprint.capabilityadapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B25 module-owned adapter contracts.
 *
 * <p>This catalog is intentionally explicit. It is not a generic JSON writer and it does not
 * make a module available merely because confirmed facts exist. A capability becomes writable
 * only when its named module command is registered and independently verified.</p>
 */
public final class FactoryCapabilityAdapterContracts {

    public static final String SCHEMA_VERSION = "factory-capability-adapter-contract/v1";
    public static final String READINESS_SCHEMA_VERSION = "factory-capability-adapter-readiness/v1";

    private FactoryCapabilityAdapterContracts() {
    }

    public enum AdapterMode {
        READ_ONLY_IDENTITY,
        READ_ONLY_PREVIEW,
        MODULE_DRAFT
    }

    public enum Availability {
        AVAILABLE_READ_ONLY,
        AVAILABLE_DRAFT,
        BLOCKED_OWNER_COMMAND_MISSING
    }

    public record CapabilityContract(
            String capabilityId,
            String capabilityGroup,
            String moduleOwner,
            String artifactType,
            String targetSchemaVersion,
            AdapterMode adapterMode,
            String requiredPermission,
            List<String> dependencies,
            List<String> allowedTargetStates,
            List<String> readbackAssertions,
            String recoveryStrategy,
            String dedicatedCommand) {
        public CapabilityContract {
            capabilityId = clean(capabilityId);
            capabilityGroup = clean(capabilityGroup);
            moduleOwner = clean(moduleOwner);
            artifactType = clean(artifactType);
            targetSchemaVersion = clean(targetSchemaVersion);
            adapterMode = Objects.requireNonNull(adapterMode, "adapterMode");
            requiredPermission = clean(requiredPermission);
            dependencies = immutable(dependencies);
            allowedTargetStates = immutable(allowedTargetStates);
            readbackAssertions = immutable(readbackAssertions);
            recoveryStrategy = clean(recoveryStrategy);
            dedicatedCommand = clean(dedicatedCommand);
        }
    }

    public record AdapterReadiness(
            String capabilityId,
            Availability availability,
            String moduleOwner,
            String artifactType,
            String targetSchemaVersion,
            String requiredPermission,
            List<String> dependencies,
            List<String> allowedTargetStates,
            List<String> readbackAssertions,
            String recoveryStrategy,
            String dedicatedCommand,
            String blockingCode,
            String nextStep,
            int expectedBusinessWriteCount,
            String publishState,
            String activationState) {
        public AdapterReadiness {
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            expectedBusinessWriteCount = availability == Availability.AVAILABLE_DRAFT ? 1 : 0;
        }
    }

    public record ReadinessReport(
            String schemaVersion,
            List<AdapterReadiness> capabilities,
            int capabilityCount,
            int availableReadOnlyCount,
            int availableDraftCount,
            int blockedCount,
            int expectedBusinessWriteCount,
            String publishState,
            String activationState) {
        public ReadinessReport {
            schemaVersion = READINESS_SCHEMA_VERSION;
            capabilities = List.copyOf(capabilities);
            capabilityCount = capabilities.size();
            availableReadOnlyCount = (int) capabilities.stream()
                    .filter(item -> item.availability() == Availability.AVAILABLE_READ_ONLY).count();
            availableDraftCount = (int) capabilities.stream()
                    .filter(item -> item.availability() == Availability.AVAILABLE_DRAFT).count();
            blockedCount = (int) capabilities.stream()
                    .filter(item -> item.availability() == Availability.BLOCKED_OWNER_COMMAND_MISSING).count();
            expectedBusinessWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    /**
     * Write/activation-order dependencies of each capability's module command.
     *
     * <p>⚠️ This is <b>not</b> the same relation as the versioned capability catalog's
     * {@code depends_on} ({@code factory_capability_definitions}, see
     * {@code FactoryBlueprintCapabilityCatalog}): the catalog says what must be confirmed before a
     * capability may enter review; these lists say which DRAFTs must already exist before the
     * module command can write. For 23 of the 26 capabilities the two are identical, and the 13
     * module commands that read the catalog compare it against {@link #dependenciesOf} at write
     * time. Three differ on purpose, pinned by {@code FactoryCapabilityAdapterContractsTest}:
     * <ul>
     *   <li>{@code workflow}: the catalog row is <em>human publication</em> (after routes, BOM and
     *       critical controls are reviewed); here it is <em>DRAFT materialization</em>, which only
     *       needs routes/products/materials/processes.</li>
     *   <li>{@code bom}: a BOM DRAFT pins a Workflow DRAFT revision and a packaging snapshot, so it
     *       writes after them; the catalog only orders its review after products/materials/processes.</li>
     *   <li>{@code routes}: the route compiler only needs processes; products are a review prerequisite.</li>
     * </ul>
     * Splitting {@code workflow} into draft and publication nodes, the union of both relations is
     * acyclic (draft → BOM → critical controls → publication).</p>
     */
    public static Registry b25Defaults() {
        return new Registry(List.of(
                c("factory", "factory-foundation", "Factory identity", "FACTORY_SCOPE", "factory-scope/v1",
                        AdapterMode.READ_ONLY_IDENTITY, "factory:read", List.of(), List.of("READ_ONLY"),
                        List.of("factoryId unchanged", "tenant scope exact"), "no mutation", "FactoryScopeReader"),
                c("products", "master-data", "ProductType", "PRODUCT_DRAFT", "product-draft/v1",
                        AdapterMode.MODULE_DRAFT, "product:draft:write", List.of("factory"), List.of("DRAFT"),
                        List.of("same factory", "natural key exact", "version exact"), "create a corrective DRAFT", "ProductTypeDraftCommand"),
                c("materials", "master-data", "RawMaterialType", "MATERIAL_DRAFT", "material-draft/v1",
                        AdapterMode.MODULE_DRAFT, "material:draft:write", List.of("factory"), List.of("DRAFT"),
                        List.of("same factory", "category and unit resolved", "version exact"), "create a corrective DRAFT", "MaterialTypeDraftCommand"),
                c("supplier-receiving", "supply-incoming", "Supplier and receiving policy", "SUPPLIER_RECEIVING_TEMPLATE", "supplier-receiving-template/v1",
                        AdapterMode.MODULE_DRAFT, "supplier:policy:draft", List.of("materials"), List.of("DRAFT", "TEMPLATE"),
                        List.of("supplier scope exact", "no purchase or inventory document created"), "archive the new template", "SupplierReceivingPolicyDraftCommand"),
                c("processes", "master-data", "WorkProcess", "PROCESS_DRAFT", "work-process-draft/v1",
                        AdapterMode.MODULE_DRAFT, "process:draft:write", List.of("factory"), List.of("DRAFT"),
                        List.of("same factory", "canonical process identity", "version exact"), "create a corrective DRAFT", "WorkProcessDraftCommand"),
                c("routes", "process-flow", "Factory Blueprint route compiler", "ROUTE_GRAPH_PREVIEW", "factory-route-graph/v1",
                        AdapterMode.READ_ONLY_PREVIEW, "factory-blueprint:read", List.of("processes"), List.of("PREVIEW_ONLY"),
                        List.of("topology complete", "NOT_PUBLISHED"), "recompile from confirmed facts", "FactoryRouteArtifactAdapter"),
                c("bom", "formula", "BomRecipe", "BOM_DRAFT", "bom-draft/v1",
                        AdapterMode.MODULE_DRAFT, "bom:draft:write", List.of("products", "materials", "workflow", "packaging"), List.of("DRAFT"),
                        List.of("same factory", "all quantities and units resolved", "workflow revision pin exact"), "create a new BOM DRAFT version", "BomRecipeDraftCommand"),
                c("hazard-analysis", "food-safety", "HACCP hazard analysis", "HAZARD_ANALYSIS_DRAFT", "hazard-analysis-draft/v1",
                        AdapterMode.MODULE_DRAFT, "haccp:draft:write", List.of("routes", "bom"), List.of("DRAFT"),
                        List.of("hazard evidence retained", "process links exact"), "supersede with a reviewed DRAFT", "HazardAnalysisDraftCommand"),
                c("critical-controls", "food-safety", "HACCP checkpoint", "CRITICAL_CONTROL_DRAFT", "critical-control-draft/v1",
                        AdapterMode.MODULE_DRAFT, "haccp:ccp:draft", List.of("hazard-analysis"), List.of("DRAFT"),
                        List.of("critical limit basis retained", "monitoring and correction complete"), "supersede with a reviewed DRAFT", "CriticalControlDraftCommand"),
                c("allergen-label", "food-safety", "Allergen and label", "ALLERGEN_LABEL_DRAFT", "allergen-label-draft/v1",
                        AdapterMode.MODULE_DRAFT, "label:draft:write", List.of("products", "materials", "routes"), List.of("DRAFT"),
                        List.of("source matrix complete", "label version exact", "changeover verification present"), "retain prior label version", "AllergenLabelDraftCommand"),
                c("sanitation-cip", "sanitation", "Sanitation and CIP", "SANITATION_CIP_TEMPLATE", "sanitation-cip-template/v1",
                        AdapterMode.MODULE_DRAFT, "sanitation:template:draft", List.of("routes"), List.of("DRAFT", "TEMPLATE"),
                        List.of("chemical concentration and time typed", "verification method present"), "archive the new template", "SanitationCipDraftCommand"),
                c("environmental-monitoring", "sanitation", "Environmental monitoring", "ENVIRONMENTAL_MONITORING_TEMPLATE", "environmental-monitoring-template/v1",
                        AdapterMode.MODULE_DRAFT, "environment:template:draft", List.of("sanitation-cip"), List.of("DRAFT", "TEMPLATE"),
                        List.of("zone and sampling plan exact", "response threshold typed"), "archive the new template", "EnvironmentalMonitoringDraftCommand"),
                c("hygienic-zones", "sanitation", "Hygienic zoning", "HYGIENIC_ZONE_DRAFT", "hygienic-zone-draft/v1",
                        AdapterMode.MODULE_DRAFT, "hygiene-zone:draft:write", List.of("factory"), List.of("DRAFT"),
                        List.of("people material air flows explicit", "zone identity stable"), "create a corrective DRAFT", "HygienicZoneDraftCommand"),
                c("warehouses", "operations-resource", "FactoryWarehouse", "WAREHOUSE_CONTROL_DRAFT", "warehouse-control-draft/v1",
                        AdapterMode.MODULE_DRAFT, "warehouse:draft:write", List.of("factory"), List.of("DRAFT"),
                        List.of("no inventory quantity changed", "cold-chain limits typed"), "create a corrective DRAFT", "WarehouseControlDraftCommand"),
                c("packaging", "master-data", "Product packaging", "PACKAGING_DRAFT", "packaging-draft/v1",
                        AdapterMode.MODULE_DRAFT, "packaging:draft:write", List.of("products", "materials"), List.of("DRAFT"),
                        List.of("pack unit conversion exact", "label reference exact"), "create a corrective DRAFT", "PackagingDraftCommand"),
                c("equipment-calibration", "operations-resource", "FactoryEquipment", "EQUIPMENT_PLAN_DRAFT", "equipment-plan-draft/v1",
                        AdapterMode.MODULE_DRAFT, "equipment:plan:draft", List.of("processes"), List.of("DRAFT", "PLAN"),
                        List.of("equipment identity exact", "maintenance and calibration separate"), "archive the new plan", "EquipmentCalibrationPlanDraftCommand"),
                c("quality-release", "quality", "Quality inspection and release", "QUALITY_RELEASE_TEMPLATE", "quality-release-template/v1",
                        AdapterMode.MODULE_DRAFT, "quality:template:draft", List.of("critical-controls", "materials"), List.of("DRAFT", "TEMPLATE"),
                        List.of("sampling and disposition complete", "no real inspection created"), "archive the new template", "QualityReleaseTemplateDraftCommand"),
                c("deviation-capa", "quality", "Deviation and CAPA", "DEVIATION_CAPA_TEMPLATE", "deviation-capa-template/v1",
                        AdapterMode.MODULE_DRAFT, "quality:capa:template:draft", List.of("critical-controls", "quality-release"), List.of("DRAFT", "TEMPLATE"),
                        List.of("classification and approval route complete", "no real CAPA case created"), "archive the new template", "DeviationCapaTemplateDraftCommand"),
                c("trace-recall", "traceability", "Trace and recall", "TRACE_RECALL_PLAN_DRAFT", "trace-recall-plan-draft/v1",
                        AdapterMode.MODULE_DRAFT, "trace:plan:draft",
                        List.of("routes", "bom", "warehouses", "quality-release"),
                        List.of("DRAFT", "PLAN"),
                        List.of("lineage scope explicit", "no real recall created"), "archive the new plan", "TraceRecallPlanDraftCommand"),
                c("accounts", "people-access", "Account role and training", "ROLE_TRAINING_DRAFT", "role-training-draft/v1",
                        AdapterMode.MODULE_DRAFT, "account:role:draft", List.of("factory"), List.of("DRAFT"),
                        List.of("no invitation sent", "no role granted", "segregation of duties checked"), "remove unapproved DRAFT", "RoleTrainingDraftCommand"),
                c("modules", "platform", "Factory module configuration", "MODULE_CONFIG_DRAFT", "module-config-draft/v1",
                        AdapterMode.MODULE_DRAFT, "module:config:draft", List.of("factory"), List.of("DRAFT"),
                        List.of("legal records remain readable", "dependency closure complete"), "restore prior module DRAFT", "ModuleConfigDraftCommand"),
                c("forms", "platform", "Dynamic form schema", "FORM_SCHEMA_DRAFT", "form-schema-draft/v1",
                        AdapterMode.MODULE_DRAFT, "form:schema:draft", List.of("modules"), List.of("DRAFT"),
                        List.of("schema validates", "field identities stable"), "restore prior schema version", "FormSchemaDraftCommand"),
                c("alerts", "platform", "Alert policy", "ALERT_POLICY_DRAFT", "alert-policy-draft/v1",
                        AdapterMode.MODULE_DRAFT, "alert:policy:draft", List.of("modules"), List.of("DRAFT"),
                        List.of("recipient roles resolved", "no notification emitted"), "archive the new policy", "AlertPolicyDraftCommand"),
                c("contract-material", "supply-incoming", "Customer-owned material", "CONTRACT_MATERIAL_POLICY_DRAFT", "contract-material-policy-draft/v1",
                        AdapterMode.MODULE_DRAFT, "contract-material:policy:draft", List.of("materials", "supplier-receiving"), List.of("DRAFT"),
                        List.of("customer ownership explicit", "no AP or inventory transaction created"), "archive the new policy", "ContractMaterialPolicyDraftCommand"),
                c("emergency-defense", "traceability", "Emergency and food defense", "EMERGENCY_DEFENSE_PLAN_DRAFT", "emergency-defense-plan-draft/v1",
                        AdapterMode.MODULE_DRAFT, "emergency:plan:draft", List.of("trace-recall", "alerts"), List.of("DRAFT", "PLAN"),
                        List.of("escalation roles resolved", "no real emergency action created"), "archive the new plan", "EmergencyDefensePlanDraftCommand"),
                c("workflow", "process-flow", "ProductProcessWorkflow", "WORKFLOW_DRAFT", "product-process-workflow/v1",
                        AdapterMode.MODULE_DRAFT, "workflow:draft:materialize", List.of("routes", "products", "materials", "processes"), List.of("DRAFT"),
                        List.of("revision and hash exact", "graph identity exact", "NOT_ACTIVE"), "SNAPSHOT or create a corrective DRAFT", "FactoryWorkflowDraftMaterializationService")
        ));
    }

    private static final Registry DEFAULTS = b25Defaults();

    /**
     * The dependency list a module DRAFT command must find on its blueprint capability. Module
     * commands read it from here instead of keeping their own copy (13 hand-written copies used to
     * be the third version of this graph).
     */
    public static List<String> dependenciesOf(String capabilityId) {
        return DEFAULTS.require(capabilityId).dependencies();
    }

    /**
     * Commands with implementation, typed persistence, readback, recovery and target tests on the
     * current product tree. This list is intentionally explicit: classpath presence alone must not
     * promote a capability.
     */
    public static ReadinessReport currentVerifiedReadiness() {
        return b25Defaults().readiness(
                Set.of("factory", "routes"),
                Set.of("products", "materials", "supplier-receiving", "processes", "modules",
                        "hygienic-zones", "warehouses", "workflow", "equipment-calibration",
                        "packaging", "bom", "hazard-analysis", "critical-controls", "quality-release",
                        "deviation-capa", "trace-recall", "sanitation-cip",
                        "environmental-monitoring", "allergen-label", "contract-material", "forms",
                        "alerts", "accounts", "emergency-defense"));
    }

    public static final class Registry {
        private final Map<String, CapabilityContract> contracts;

        Registry(List<CapabilityContract> values) {
            Map<String, CapabilityContract> indexed = new LinkedHashMap<>();
            for (CapabilityContract value : values) {
                CapabilityContract duplicate = indexed.putIfAbsent(value.capabilityId(), value);
                if (duplicate != null) throw new IllegalArgumentException("Duplicate capability contract: " + value.capabilityId());
            }
            validate(indexed);
            contracts = Map.copyOf(indexed);
        }

        public List<CapabilityContract> contracts() {
            return contracts.values().stream().sorted(Comparator.comparing(CapabilityContract::capabilityId)).toList();
        }

        /**
         * Capabilities in an order in which each one's write dependencies come first (Kahn, ties
         * broken by capability id, so the order is deterministic). This is the order DRAFTs can be
         * written in — e.g. {@code bom} after {@code workflow} and {@code packaging} — which is
         * <b>not</b> the catalog's review order (see {@link #b25Defaults()}).
         */
        public List<CapabilityContract> writeOrder() {
            Map<String, Integer> remaining = new LinkedHashMap<>();
            contracts.values().forEach(c -> remaining.put(c.capabilityId(), c.dependencies().size()));
            java.util.TreeSet<String> ready = new java.util.TreeSet<>();
            remaining.forEach((id, count) -> { if (count == 0) ready.add(id); });
            List<CapabilityContract> ordered = new ArrayList<>();
            while (!ready.isEmpty()) {
                String next = ready.pollFirst();
                ordered.add(contracts.get(next));
                for (CapabilityContract c : contracts.values()) {
                    if (c.dependencies().contains(next) && remaining.merge(c.capabilityId(), -1, Integer::sum) == 0) {
                        ready.add(c.capabilityId());
                    }
                }
            }
            if (ordered.size() != contracts.size()) {
                throw new IllegalStateException("Capability write order is not a DAG"); // validate() already rejects cycles
            }
            return List.copyOf(ordered);
        }

        public CapabilityContract require(String capabilityId) {
            CapabilityContract contract = contracts.get(clean(capabilityId));
            if (contract == null) throw new IllegalArgumentException("Unknown capability: " + capabilityId);
            return contract;
        }

        public ReadinessReport readiness(Set<String> registeredReadOnlyAdapters, Set<String> registeredDraftCommands) {
            Set<String> readOnly = immutableSet(registeredReadOnlyAdapters);
            Set<String> draft = immutableSet(registeredDraftCommands);
            rejectUnknownRegistrations(readOnly, "read-only adapter");
            rejectUnknownRegistrations(draft, "draft command");
            List<AdapterReadiness> items = contracts().stream().map(contract -> readiness(contract, readOnly, draft)).toList();
            return new ReadinessReport(READINESS_SCHEMA_VERSION, items, 0, 0, 0, 0, 0,
                    "NOT_PUBLISHED", "NOT_ACTIVATED");
        }

        private AdapterReadiness readiness(CapabilityContract contract, Set<String> readOnly, Set<String> draft) {
            Availability availability;
            String blockingCode = "";
            String nextStep;
            if (contract.adapterMode() == AdapterMode.READ_ONLY_IDENTITY
                    || contract.adapterMode() == AdapterMode.READ_ONLY_PREVIEW) {
                if (readOnly.contains(contract.capabilityId())) {
                    availability = Availability.AVAILABLE_READ_ONLY;
                    nextStep = "可读取或编译预览；不会产生业务写";
                } else {
                    availability = Availability.BLOCKED_OWNER_COMMAND_MISSING;
                    blockingCode = "READ_ONLY_ADAPTER_NOT_REGISTERED";
                    nextStep = "注册并验证命名只读 adapter";
                }
            } else if (draft.contains(contract.capabilityId())) {
                availability = Availability.AVAILABLE_DRAFT;
                nextStep = "可进入专用模块 DRAFT command 的独立确认；publish/activate 仍禁止";
            } else {
                availability = Availability.BLOCKED_OWNER_COMMAND_MISSING;
                blockingCode = "MODULE_DRAFT_COMMAND_NOT_REGISTERED";
                nextStep = "由 " + contract.moduleOwner() + " 实现并验证 " + contract.dedicatedCommand();
            }
            return new AdapterReadiness(contract.capabilityId(), availability, contract.moduleOwner(),
                    contract.artifactType(), contract.targetSchemaVersion(), contract.requiredPermission(),
                    contract.dependencies(), contract.allowedTargetStates(), contract.readbackAssertions(),
                    contract.recoveryStrategy(), contract.dedicatedCommand(), blockingCode, nextStep, 0,
                    "NOT_PUBLISHED", "NOT_ACTIVATED");
        }

        private void validate(Map<String, CapabilityContract> indexed) {
            if (indexed.size() != 26) throw new IllegalArgumentException("B25 must classify exactly 26 capabilities");
            for (CapabilityContract contract : indexed.values()) {
                requireFields(contract);
                for (String dependency : contract.dependencies()) {
                    if (!indexed.containsKey(dependency)) {
                        throw new IllegalArgumentException(contract.capabilityId() + " has unknown dependency " + dependency);
                    }
                    if (dependency.equals(contract.capabilityId())) {
                        throw new IllegalArgumentException(contract.capabilityId() + " cannot depend on itself");
                    }
                }
                if (contract.adapterMode() == AdapterMode.MODULE_DRAFT
                        && !contract.allowedTargetStates().stream().allMatch(Set.of("DRAFT", "TEMPLATE", "PLAN")::contains)) {
                    throw new IllegalArgumentException(contract.capabilityId() + " exposes a non-draft target state");
                }
            }
            detectDependencyCycles(indexed);
        }

        private void requireFields(CapabilityContract contract) {
            if (List.of(contract.capabilityId(), contract.capabilityGroup(), contract.moduleOwner(),
                    contract.artifactType(), contract.targetSchemaVersion(), contract.requiredPermission(),
                    contract.recoveryStrategy(), contract.dedicatedCommand()).stream().anyMatch(String::isBlank)
                    || contract.allowedTargetStates().isEmpty() || contract.readbackAssertions().isEmpty()) {
                throw new IllegalArgumentException("Incomplete capability contract: " + contract.capabilityId());
            }
        }

        private void detectDependencyCycles(Map<String, CapabilityContract> indexed) {
            Set<String> complete = new LinkedHashSet<>();
            for (String capabilityId : indexed.keySet()) visit(capabilityId, indexed, complete, new LinkedHashSet<>());
        }

        private void visit(String capabilityId, Map<String, CapabilityContract> indexed,
                           Set<String> complete, Set<String> active) {
            if (complete.contains(capabilityId)) return;
            if (!active.add(capabilityId)) throw new IllegalArgumentException("Capability dependency cycle: " + active);
            for (String dependency : indexed.get(capabilityId).dependencies()) visit(dependency, indexed, complete, active);
            active.remove(capabilityId);
            complete.add(capabilityId);
        }

        private void rejectUnknownRegistrations(Set<String> registered, String type) {
            List<String> unknown = registered.stream().filter(item -> !contracts.containsKey(item)).sorted().toList();
            if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown " + type + ": " + unknown);
        }
    }

    private static CapabilityContract c(String id, String group, String owner, String artifact,
                                        String schema, AdapterMode mode, String permission,
                                        List<String> dependencies, List<String> targetStates,
                                        List<String> readback, String recovery, String command) {
        return new CapabilityContract(id, group, owner, artifact, schema, mode, permission,
                dependencies, targetStates, readback, recovery, command);
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().map(FactoryCapabilityAdapterContracts::clean).toList();
    }

    private static Set<String> immutableSet(Set<String> values) {
        if (values == null) return Set.of();
        return Set.copyOf(values.stream().map(FactoryCapabilityAdapterContracts::clean).toList());
    }
}
