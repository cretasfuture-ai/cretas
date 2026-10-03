/** Executes the actual screen's lifecycle and send declarations with synthetic boundaries.
 * No screen rendering, network, provider, microphone or device claims are made here.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import ts from 'typescript';
const source = fs.readFileSync(path.resolve(__dirname, '../../screens/factory-admin/ai-analysis/AIChatScreen.tsx'), 'utf8');
const ast = ts.createSourceFile('AIChatScreen.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
type Context = Record<string, unknown>;
function declaration(name: string): string {
    let found: ts.VariableDeclaration | undefined;
    function walk(node: ts.Node): void {
        if (ts.isVariableDeclaration(node) && node.name.getText(ast) === name)
            found = node;
        ts.forEachChild(node, walk);
    }
    walk(ast);
    assert(found, name);
    return `const ${found.getText(ast)};globalThis.extracted=${name};`;
}
function run<T>(code: string, context: Context): T {
    const sandbox = vm.createContext({ console: { log() { }, warn() { }, error() { } }, Date, Math, JSON, Error, ...context });
    const output = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } });
    vm.runInContext(output.outputText, sandbox);
    return sandbox.extracted as T;
}
function extract<T>(name: string, context: Context): T { return run<T>(declaration(name), context); }
function lifecycle(context: Context): () => () => void {
    let call: ts.CallExpression | undefined;
    function walk(node: ts.Node): void {
        if (ts.isCallExpression(node) && node.expression.getText(ast) === 'useEffect'
            && node.arguments[0]?.getText(ast).includes('previewTimer.current'))
            call = node;
        ts.forEachChild(node, walk);
    }
    walk(ast);
    assert(call, 'preview lifecycle effect');
    let setup!: () => () => void;
    run(call.getText(ast) + ';', { ...context, useEffect: (fn: () => () => void) => { setup = fn; } });
    return () => setup();
}
function preview() {
    let time = 0, next = 0, view = '';
    const tasks = new Map<number, {
        fn: () => void;
        at: number;
    }>();
    const all = new Map<number, {
        fn: () => void;
        at: number;
    }>();
    const sends: string[] = [];
    const context = {
        previewTimer: { current: null as number | null }, previewGenerationRef: { current: 0 }, isMountedRef: { current: true },
        setPreviewText: (value: string) => { view = value; }, handleSend: (value: string) => { sends.push(value); },
        useCallback: <T,>(fn: T): T => fn,
        setTimeout: (fn: () => void, ms: number) => {
            const id = ++next, task = { fn, at: time + ms };
            tasks.set(id, task);
            all.set(id, task);
            return id;
        },
        clearTimeout: (id: number) => { tasks.delete(id); },
    };
    const mount = lifecycle(context);
    let cleanup = mount();
    return {
        sends, handle: extract<(text: string) => void>('handleVoiceResult', context),
        cancel: extract<() => void>('cancelVoicePreview', context), get text() { return view; }, get last() { return next; },
        tick(ms: number) { time += ms; for (const [id, task] of [...tasks])
            if (task.at <= time) {
                tasks.delete(id);
                task.fn();
            } },
        queued(id: number) { assert(all.has(id)); all.get(id)!.fn(); },
        unmount() { cleanup(); }, remount() { cleanup = mount(); },
    };
}
type Message = Record<string, unknown>;
type Callbacks = {
    onToken: (text: string) => void;
    onResult: (result: Record<string, unknown>) => void;
    onComplete: (result: Record<string, unknown>) => void;
    onError: (message: string) => void;
};
type StreamCall = [
    string,
    Callbacks,
    string,
    string
];
function sending(behavior?: (args: StreamCall) => void | Promise<void>) {
    const state = { calls: [] as StreamCall[], messages: [] as Message[], loading: [] as boolean[], audio: 0 };
    let id = 0;
    const refs = { isMountedRef: { current: true }, sendPendingRef: { current: false } };
    const ctx = {
        ...refs, inputText: '', isLoading: false, currentFactoryId: 'SYNTHETIC_FACTORY', user: { id: 1 },
        userRole: 'production_manager', entityType: 'SALES_ORDER', sceneConfig: null,
        setInputText() { }, setIsLoading: (value: boolean) => { state.loading.push(value); }, setStreamStatus() { },
        detectAnalysisMode: (): {
            mode: string;
        } => ({ mode: 'quick' }), generateMessageId: () => String(++id),
        setMessages: (update: (previous: Message[]) => Message[]) => { state.messages = update(state.messages); },
        scrollToBottom() { }, detectRichData: () => null, parseRestaurantAgentLaunch: () => undefined, t: (key: string) => key,
        feedbackSounds: { onError: () => { state.audio++; }, onWriteSuccess: () => { state.audio++; }, onNeedMoreInfo: () => { state.audio++; } },
        aiApiClient: { executeIntentStream: async (...args: StreamCall) => { state.calls.push(args); if (behavior)
                await behavior(args); } },
    };
    return { send: extract<(text: string) => Promise<void>>('handleSend', ctx), state, refs, ctx };
}
test('latest voice transcript replaces the previous timer', () => { const v = preview(); v.handle('旧语音'); v.tick(800); v.handle('新语音'); v.tick(700); assert.deepEqual(v.sends, []); v.tick(800); assert.deepEqual(v.sends, ['新语音']); });
test('cancel after replacement leaves no old or new send', () => { const v = preview(); v.handle('旧语音'); v.tick(500); v.handle('新语音'); v.cancel(); v.tick(2500); assert.deepEqual(v.sends, []); assert.equal(v.text, ''); });
test('already queued old callback cannot overwrite or send newer preview', () => { const v = preview(); v.handle('旧语音'); const old = v.last; v.handle('新语音'); v.queued(old); assert.deepEqual(v.sends, []); assert.equal(v.text, '新语音'); v.tick(1500); assert.deepEqual(v.sends, ['新语音']); });
test('already queued cancelled callback cannot send after cancellation', () => { const v = preview(); v.handle('取消语音'); const id = v.last; v.cancel(); v.queued(id); assert.deepEqual(v.sends, []); assert.equal(v.text, ''); });
test('unmount invalidates queued callback and late transcript', () => { const v = preview(); v.handle('未提交语音'); const id = v.last; v.unmount(); v.queued(id); v.handle('卸载后迟到识别'); v.tick(2000); assert.deepEqual(v.sends, []); });
test('effect remount allows a new voice but rejects old generation', () => { const v = preview(); v.handle('旧挂载'); const id = v.last; v.unmount(); v.remount(); v.handle('新挂载'); v.queued(id); v.tick(1500); assert.deepEqual(v.sends, ['新挂载']); });
test('single normal voice waits 1500ms and sends once', () => { const v = preview(); v.handle('正常语音'); v.tick(1499); assert.equal(v.sends.length, 0); v.tick(1); v.tick(1500); assert.deepEqual(v.sends, ['正常语音']); });
test('cancel followed by explicit new voice remains usable', () => { const v = preview(); v.handle('取消'); v.cancel(); v.handle('明确再次输入'); v.tick(1500); assert.deepEqual(v.sends, ['明确再次输入']); });
test('two same-render presses create one stream while pending', async () => { let finish!: () => void; const done = new Promise<void>(r => { finish = r; }); const s = sending(() => done); const a = s.send('同一请求'); const b = s.send('同一请求'); assert.equal(s.state.calls.length, 1); finish(); await Promise.all([a, b]); assert.equal(s.refs.sendPendingRef.current, false); });
test('success releases guard for an explicit second send', async () => { const s = sending(args => { args[1].onResult({ status: 'SUCCESS', message: '合成只读成功' }); args[1].onComplete({ status: 'SUCCESS' }); }); await s.send('第一次'); await s.send('明确第二次'); assert.equal(s.state.calls.length, 2); assert.equal(s.refs.sendPendingRef.current, false); });
test('rejection releases guard without replay and allows explicit retry', async () => { let rejectFirst = true; const s = sending(() => { if (rejectFirst)
    throw new Error('合成断连'); }); await s.send('第一次'); assert.equal(s.state.calls.length, 1); assert.equal(s.refs.sendPendingRef.current, false); assert.equal(s.state.messages.at(-1)?.content, '合成断连'); rejectFirst = false; await s.send('明确重试'); assert.equal(s.state.calls.length, 2); });
test('unmount suppresses delayed response state/audio and releases pending', async () => { let finish!: () => void; const done = new Promise<void>(r => { finish = r; }); const s = sending(() => done); const pending = s.send('查询'); const callbacks = s.state.calls[0]![1]; const messages = JSON.stringify(s.state.messages); s.refs.isMountedRef.current = false; callbacks.onToken('迟到'); callbacks.onResult({ status: 'SUCCESS', message: '迟到响应', intentCategory: 'WRITE' }); callbacks.onComplete({ status: 'SUCCESS' }); callbacks.onError('迟到错误'); finish(); await pending; assert.equal(JSON.stringify(s.state.messages), messages); assert.equal(s.state.audio, 0); assert.equal(s.refs.sendPendingRef.current, false); await s.send('卸载后提交'); assert.equal(s.state.calls.length, 1); });
test('throw before request in analysis detection still releases guard', async () => { const s = sending(); s.ctx.detectAnalysisMode = () => { throw new Error('合成检测失败'); }; const send = extract<(text: string) => Promise<void>>('handleSend', s.ctx); await send('第一次'); assert.equal(s.refs.sendPendingRef.current, false); assert.equal(s.state.loading.at(-1), false); s.ctx.detectAnalysisMode = () => ({ mode: 'quick' }); await extract<(text: string) => Promise<void>>('handleSend', s.ctx)('明确再发'); assert.equal(s.state.calls.length, 1); });
