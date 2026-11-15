// Legacy callers may omit the source; omission must never invoke cloud inference.
exports.inferenceSource=requested=>requested==='local'?'local':requested==='online'?'online':'macmini';
