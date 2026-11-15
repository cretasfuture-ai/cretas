/* Modern-view compatibility only: keep the captured original bytes intact. */
const before='Mt(Ce),G.value=Me,Ns(),Jn.value=!1,ne.value=!1,await $o({force:!0});const ye=le.data.planRebind';
const after='Mt(Ce),G.value=Me,Ns(),Jn.value=!1,ne.value=!1,await $o({force:!0}),await Oa(),te.value=null,document.dispatchEvent(new CustomEvent("ydwm-configuration-published"));const ye=le.data.planRebind';
exports.patchPublishRefresh=text=>text.includes(before)?text.replace(before,after):text;
exports.before=before;
exports.after=after;
