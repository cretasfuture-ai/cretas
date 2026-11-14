package com.cretas.aims.service.intent.flywheel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同一会话里同一个名字用同一个代号 (Steve 2026-11-14: 同会话同客户编号一致)。
 *
 * <p>只在内存里 (按访问顺序淘汰最久没用的会话); 名字 → 代号的对照<b>不落库</b> —— 落了就等于把原名存在旁边。
 * 进程重启后同一会话的代号从 A 重新编 (导出只要求一句话、一段上下文内一致, 跨重启不保证, 写明而不是假装)。
 * 飞轮写入在单线程队列上做, 这里仍加锁 (测试与同步模式直接在请求线程上调)。</p>
 */
final class FlywheelSessionAliases {

    private final Map<String, Book> books;

    FlywheelSessionAliases(int maxSessions) {
        this.books = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Book> eldest) {
                return size() > maxSessions;
            }
        };
    }

    /** sessionKey 为 null → 一次性的本子 (只在本句内一致)。 */
    synchronized Book book(String sessionKey) {
        if (sessionKey == null) {
            return new Book();
        }
        return books.computeIfAbsent(sessionKey, k -> new Book());
    }

    static final class Book {
        private final Map<String, Map<String, String>> byKind = new HashMap<>();

        synchronized String alias(String kind, String identity) {
            Map<String, String> names = byKind.computeIfAbsent(kind, k -> new HashMap<>());
            return names.computeIfAbsent(identity, k -> letters(names.size()));
        }
    }

    /** 0 → A, 25 → Z, 26 → AA … */
    static String letters(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}
