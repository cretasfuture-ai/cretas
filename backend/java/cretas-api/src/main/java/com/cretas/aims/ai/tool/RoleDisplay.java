package com.cretas.aims.ai.tool;

import com.cretas.aims.entity.enums.FactoryUserRole;

import java.util.ArrayList;
import java.util.List;

/** 角色码 → 中文角色名 (拒答文案不给一线用户看 finance_manager 这类内部码); 不认识的码显示「相应角色」。 */
public final class RoleDisplay {
    private RoleDisplay() {}

    public static String of(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) return "相应角色";
        try {
            return FactoryUserRole.valueOf(roleCode.trim()).getDisplayName();
        } catch (IllegalArgumentException e) {
            return "相应角色";
        }
    }

    /** 「[a, b]」/「a,b」/ JSON 数组形式的角色清单 → 「角色甲、角色乙」(去重; 一个都不认识时「相应角色」)。 */
    public static String listOf(String rolesText) {
        if (rolesText == null || rolesText.isBlank()) return "相应角色";
        List<String> out = new ArrayList<>();
        for (String part : rolesText.replaceAll("[\\[\\]\"']", "").split("[,，;\\s]+")) {
            if (part.isBlank()) continue;
            String label = of(part);
            if (!"相应角色".equals(label) && !out.contains(label)) out.add(label);
        }
        return out.isEmpty() ? "相应角色" : String.join("、", out);
    }
}
