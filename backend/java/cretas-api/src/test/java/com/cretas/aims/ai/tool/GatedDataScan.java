package com.cretas.aims.ai.tool;

import com.cretas.aims.annotation.RequirePermission;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-method, bytecode-level map of who reads which data (S6 gated-data rule).
 *
 * <p>For every handler method of every {@code @RestController} it records the service / repository /
 * mapper methods the handler calls (one hop) and the handler's effective {@code @RequirePermission}
 * (method-level, otherwise class-level, otherwise ungated). For every tool it records the same kind of
 * calls (all methods of the tool class, including lambda bodies and its superclasses up to the
 * framework base). A data method is <b>gated</b> when every handler that calls it is gated; the codes
 * that admit a caller to it are the union of those handlers' codes.</p>
 */
final class GatedDataScan {

    /** A service / repository / mapper method, as {@code owner#name} (owner = simple class name). */
    record DataMethod(String owner, String name) {
        @Override public String toString() { return owner + "#" + name; }
    }

    /** One HTTP handler: where it is, its effective codes and roles (both empty = ungated) and requireAll. */
    record Endpoint(String controller, String method, Set<String> codes, Set<String> roles, boolean requireAll,
                    Set<String> alsoAny) {
        boolean gated() { return !codes.isEmpty() || !roles.isEmpty(); }
        @Override public String toString() { return controller + "#" + method; }
    }

    final Map<DataMethod, List<Endpoint>> servedBy = new HashMap<>();
    /** Every read handler by {@code Controller#method}. */
    final Map<String, Endpoint> readEndpoints = new HashMap<>();
    /**
     * Data methods called by write handlers (POST / PUT / PATCH / DELETE): a write tool doing what a write
     * endpoint does (look the purchase order up, then record the receipt) is judged against that endpoint too.
     */
    final Map<DataMethod, List<Endpoint>> writeServedBy = new HashMap<>();
    /** Read handler ({@code Controller#method}) → data methods it calls directly. */
    final Map<String, Set<DataMethod>> handlerCalls = new HashMap<>();
    final Map<String, Set<DataMethod>> toolCalls = new LinkedHashMap<>();
    final Map<String, Class<?>> toolClasses = new LinkedHashMap<>();
    final List<String> uninstantiable = new ArrayList<>();
    final Map<String, ToolExecutor> instances = new LinkedHashMap<>();

    /** No-arg constructor when there is one; otherwise an instance without running a constructor. */
    static ToolExecutor instance(Class<?> c) {
        try {
            return (ToolExecutor) c.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException noDefault) {
            try {
                return (ToolExecutor) new org.objenesis.ObjenesisStd().newInstance(c);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
    int controllers;
    int handlers;

    static GatedDataScan run() throws Exception {
        GatedDataScan scan = new GatedDataScan();
        scan.scanControllers();
        scan.scanTools();
        return scan;
    }

    private static boolean isDataOwner(String internalName) {
        if (!internalName.startsWith("com/cretas/aims/")) return false;
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        if (simple.contains("$")) simple = simple.substring(0, simple.indexOf('$'));
        return simple.endsWith("Service") || simple.endsWith("ServiceImpl") || simple.endsWith("Repository")
                || simple.endsWith("Mapper") || simple.endsWith("Calculator") || simple.endsWith("Engine");
    }

    private static String simpleName(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        return simple.endsWith("Impl") ? simple.substring(0, simple.length() - 4) : simple;
    }

    /** method name+descriptor → data methods it calls (directly, plus private helpers/lambdas of the class). */
    private static Map<String, Set<DataMethod>> callsPerMethod(Class<?> type) throws IOException {
        Map<String, Set<DataMethod>> direct = new HashMap<>();
        Map<String, Set<String>> localCalls = new HashMap<>();
        String self = type.getName().replace('.', '/');
        try (InputStream in = type.getResourceAsStream("/" + self + ".class")) {
            if (in == null) return direct;
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    String key = name + desc;
                    Set<DataMethod> data = direct.computeIfAbsent(key, k -> new HashSet<>());
                    Set<String> local = localCalls.computeIfAbsent(key, k -> new HashSet<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                            if (owner.equals(self)) {
                                local.add(mname + mdesc);
                            } else if (isDataOwner(owner)) {
                                data.add(new DataMethod(simpleName(owner), mname));
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, org.springframework.asm.Handle bsm,
                                Object... args) {
                            for (Object arg : args) {
                                if (arg instanceof org.springframework.asm.Handle h) {
                                    if (h.getOwner().equals(self)) local.add(h.getName() + h.getDesc());
                                    else if (isDataOwner(h.getOwner()))
                                        data.add(new DataMethod(simpleName(h.getOwner()), h.getName()));
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        // Close over same-class helpers and lambda bodies.
        Map<String, Set<DataMethod>> closed = new HashMap<>();
        for (String method : direct.keySet()) {
            Set<DataMethod> all = new HashSet<>();
            Set<String> seen = new HashSet<>();
            List<String> todo = new ArrayList<>(List.of(method));
            while (!todo.isEmpty()) {
                String m = todo.remove(todo.size() - 1);
                if (!seen.add(m)) continue;
                all.addAll(direct.getOrDefault(m, Set.of()));
                todo.addAll(localCalls.getOrDefault(m, Set.of()));
            }
            closed.put(method, all);
        }
        return closed;
    }

    /** Read handlers: GET, or a RequestMapping that admits GET (no method restriction or GET listed). */
    private static boolean isReadHandler(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) return true;
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        if (rm == null) return false;
        return rm.method().length == 0 || Arrays.asList(rm.method())
                .contains(org.springframework.web.bind.annotation.RequestMethod.GET);
    }

    private static boolean isWriteHandler(Method m) {
        if (m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)) return true;
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        return rm != null && rm.method().length > 0 && !isReadHandler(m);
    }

    private void scanControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.controller")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            controllers++;
            Map<String, Set<DataMethod>> calls = callsPerMethod(c);
            RequirePermission onClass = c.getAnnotation(RequirePermission.class);
            com.cretas.aims.config.RequireRole rolesOnClass = c.getAnnotation(com.cretas.aims.config.RequireRole.class);
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                // Only read handlers say who may read a piece of data: a write handler that happens to
                // look a record up does not make reading that record require the write permission.
                // Write handlers are kept apart, for write tools only (see writeServedBy).
                boolean read = isReadHandler(m);
                if (!read && !isWriteHandler(m)) continue;
                if (read) handlers++;
                RequirePermission onMethod = m.getAnnotation(RequirePermission.class);
                RequirePermission effective = onMethod != null ? onMethod : onClass;
                com.cretas.aims.config.RequireRole rolesOnMethod = m.getAnnotation(com.cretas.aims.config.RequireRole.class);
                com.cretas.aims.config.RequireRole roles = rolesOnMethod != null ? rolesOnMethod : rolesOnClass;
                Endpoint endpoint = new Endpoint(c.getSimpleName(), m.getName(),
                        effective == null ? Set.of() : Set.of(effective.value()),
                        roles == null ? Set.of() : Set.of(roles.value()),
                        effective != null && effective.requireAll(),
                        effective == null ? Set.of() : Set.of(effective.alsoAny()));
                String key = m.getName() + org.springframework.asm.Type.getMethodDescriptor(m);
                if (read) {
                    readEndpoints.put(endpoint.toString(), endpoint);
                    handlerCalls.computeIfAbsent(endpoint.toString(), k -> new HashSet<>())
                            .addAll(calls.getOrDefault(key, Set.of()));
                }
                for (DataMethod data : calls.getOrDefault(key, Set.of())) {
                    (read ? servedBy : writeServedBy).computeIfAbsent(data, k -> new ArrayList<>()).add(endpoint);
                }
            }
        }
    }

    private void scanTools() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.ai.tool")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (Modifier.isAbstract(c.getModifiers()) || c.isInterface()) continue;
            // Test doubles live in test-classes; only production tools are classified.
            java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation().toString().contains("test-classes")) continue;
            ToolExecutor instance = instance(c);
            if (instance == null) {
                uninstantiable.add(c.getSimpleName());
                continue;
            }
            String name;
            try {
                name = instance.getToolName();
            } catch (RuntimeException e) {
                uninstantiable.add(c.getSimpleName());
                continue;
            }
            instances.put(name, instance);
            Set<DataMethod> calls = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
            for (Class<?> k = c; k != null && k.getName().startsWith("com.cretas.aims.ai.tool.impl"); k = k.getSuperclass()) {
                callsPerMethod(k).values().forEach(calls::addAll);
                // inner classes / lambdas compiled into nested classes
                for (Class<?> nested : k.getDeclaredClasses()) {
                    callsPerMethod(nested).values().forEach(calls::addAll);
                }
            }
            toolCalls.put(name, calls);
            toolClasses.put(name, c);
        }
    }

    /** Data methods the tool calls that only gated handlers serve → the codes admitting a caller to each. */
    Map<DataMethod, Set<String>> gatedCalls(String tool) {
        Map<DataMethod, Set<String>> gated = new LinkedHashMap<>();
        for (DataMethod data : toolCalls.getOrDefault(tool, Set.of())) {
            List<Endpoint> endpoints = servedBy.get(data);
            if (endpoints == null || endpoints.isEmpty()) continue;
            if (endpoints.stream().allMatch(Endpoint::gated)) {
                Set<String> admit = new TreeSet<>();
                // A requireAll endpoint admits only holders of all its codes: no single code of it admits anyone.
                endpoints.stream().filter(e -> !(e.requireAll() && e.codes().size() > 1))
                        .forEach(e -> admit.addAll(e.codes()));
                gated.put(data, admit);
            }
        }
        return gated;
    }

    /** Owner simple name (Impl folded, as {@link #simpleName}) → main classes implementing it. */
    private Map<String, List<String>> ownerClasses;
    private final Map<String, Map<String, Set<DataMethod>>> callsByClass = new HashMap<>();

    private Map<String, List<String>> ownerClasses() throws IOException {
        if (ownerClasses != null) return ownerClasses;
        ownerClasses = new HashMap<>();
        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource r : resolver.getResources("classpath*:com/cretas/aims/**/*.class")) {
            String url = r.getURL().toString();
            if (url.contains("test-classes")) continue;
            String internal = url.substring(url.indexOf("com/cretas/aims/"), url.length() - ".class".length());
            if (internal.contains("$") || !isDataOwner(internal)) continue;
            ownerClasses.computeIfAbsent(simpleName(internal), k -> new ArrayList<>()).add(internal.replace('/', '.'));
        }
        return ownerClasses;
    }

    /** Data methods called by every overload of {@code owner#method} in the main classes implementing the owner. */
    private Set<DataMethod> callsOf(DataMethod data) throws IOException {
        Set<DataMethod> out = new HashSet<>();
        for (String className : ownerClasses().getOrDefault(data.owner(), List.of())) {
            Map<String, Set<DataMethod>> calls = callsByClass.get(className);
            if (calls == null) {
                try {
                    calls = callsPerMethod(Class.forName(className, false, GatedDataScan.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    calls = Map.of();
                }
                callsByClass.put(className, calls);
            }
            calls.forEach((key, called) -> {
                if (key.startsWith(data.name() + "(")) out.addAll(called);
            });
        }
        return out;
    }

    /**
     * Whether a read handler reaches a method of an owner the predicate accepts, through service / repository
     * implementations (up to 6 hops). A table row or a SURFACE registration may only name handlers that do.
     */
    boolean reaches(String handler, java.util.function.Predicate<String> owner) throws IOException {
        Set<DataMethod> seen = new HashSet<>();
        List<DataMethod> frontier = new ArrayList<>(handlerCalls.getOrDefault(handler, Set.of()));
        for (int depth = 0; depth <= 6 && !frontier.isEmpty(); depth++) {
            List<DataMethod> next = new ArrayList<>();
            for (DataMethod d : frontier) {
                if (!seen.add(d)) continue;
                String base = d.owner().contains("$") ? d.owner().substring(0, d.owner().indexOf('$')) : d.owner();
                if (owner.test(base)) return true;
                next.addAll(callsOf(d));
            }
            frontier = next;
        }
        return false;
    }

    /** Read endpoints that call any method of this owner. */
    List<Endpoint> readEndpointsOfOwner(String owner) {
        List<Endpoint> all = new ArrayList<>();
        servedBy.forEach((data, endpoints) -> {
            if (data.owner().equals(owner)) all.addAll(endpoints);
        });
        return all;
    }
}
