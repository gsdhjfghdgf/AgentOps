package com.agentops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/** 数据库仅保存密文；独立本地密钥不打入 JAR 或 EXE。 */
@Component
public class ModelKeyCipher {
    private final byte[] key;
    public ModelKeyCipher(@Value("${agent.model-settings-key-file:./data/model-settings.key}") String keyFile) {
        try {
            Path path=Path.of(keyFile).toAbsolutePath();
            Files.createDirectories(path.getParent());
            if(!Files.exists(path)) {
                byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);
                try { Files.write(path,generated,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
                catch(java.nio.file.FileAlreadyExistsException ignored) { /* another process created the key */ }
                if(Files.getFileStore(path).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            key=Files.readAllBytes(path);
            if(key.length!=32)throw new IllegalStateException("模型配置加密密钥无效");
        } catch(java.io.IOException e){throw new IllegalStateException("无法读取模型配置加密密钥",e);}
    }
    public String encrypt(String value,String owner) {
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());
        } catch(java.security.GeneralSecurityException e){throw new IllegalStateException("模型密钥加密失败",e);}
    }
    public String decrypt(String value,String owner) {
        try {
            var bytes=ByteBuffer.wrap(Base64.getDecoder().decode(value));
            byte[] iv=new byte[12];bytes.get(iv);byte[] encrypted=new byte[bytes.remaining()];bytes.get(encrypted);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
        } catch(java.security.GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException e) {
            throw new IllegalStateException("模型密钥读取失败，请重新保存配置",e);
        }
    }
}
