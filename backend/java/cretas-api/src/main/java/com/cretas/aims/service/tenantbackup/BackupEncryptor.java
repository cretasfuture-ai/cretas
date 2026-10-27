package com.cretas.aims.service.tenantbackup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 把一段流式写出的内容加密成文件。服务器上只有公钥, 加密后的件我们自己也只能用离线私钥解开。 */
public interface BackupEncryptor {

    /** age 加密件的文件头 (v1)。上传前必须核对, ⛔ 不把明文传到异地。 */
    String AGE_HEADER = "age-encryption.org/v1";

    /** age X25519 公钥: {@code age1} + 58 位 bech32 字符。 */
    Pattern AGE_RECIPIENT = Pattern.compile("^age1[02-9ac-hj-np-z]{58}$");

    interface Writer { void writeTo(OutputStream out) throws IOException; }

    /**
     * @param recipient age 公钥
     * @param writer    往给出的流里写明文 (不要自己关流)
     * @param target    加密件写到这里
     */
    void encrypt(String recipient, Writer writer, Path target) throws IOException;

    static boolean validRecipient(String recipient) {
        return recipient != null && AGE_RECIPIENT.matcher(recipient).matches();
    }
}
