package com.cretas.aims.blueprint.draftentry;

import java.util.List;

/** Shared, deliberately conservative defaults for W2 proposal entries. */
final class W2DraftProposerSupport {
    private W2DraftProposerSupport() {}

    static CapabilityDraftProposer.Proposed single(String id, String label, Object request,
                                                    List<CapabilityDraftProposer.RequiredInput> inputs,
                                                    CapabilityDraftProposer.Source source) {
        return CapabilityDraftProposer.Proposed.single(id, label, request, inputs,
                List.of(new CapabilityDraftProposer.Evidence("blueprint", "v" + source.blueprint().getVersion()),
                        new CapabilityDraftProposer.Evidence("sourceDigest", source.sourceDigest())));
    }

    static CapabilityDraftProposer.RequiredInput rows(String path, String label, String why, String itemLabel) {
        return CapabilityDraftProposer.RequiredInput.list(path, label, why,
                "至少 1 行；每行字段必须满足命令约束", itemLabel, 1, null,
                List.of(CapabilityDraftProposer.RequiredInput.text("code", "编码", why,
                        "由用户填写的稳定业务编码", 128)));
    }
}
