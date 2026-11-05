package com.agentops.auth;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Desktop enrollment proof prevents an opaque-origin webpage from claiming the local workspace. */
@Component
public class RegistrationKey {
    private final byte[] expected;
    public RegistrationKey(@Value("${agent.accounts.registration-key-file:${user.home}/.agentops/registration.key}")String filename){
        try{
            Path requested=Path.of(filename).toAbsolutePath().normalize();Files.createDirectories(requested.getParent());
            // Canonicalize an administrator-provided directory once, including Windows TEMP aliases.
            Path file=requested.getParent().toRealPath().resolve(requested.getFileName());
            if(Files.isSymbolicLink(file))throw new java.io.IOException("Registration key must not be a symbolic link");
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                byte[] random=new byte[32];new SecureRandom().nextBytes(random);
                try{Files.writeString(file,Base64.getUrlEncoder().withoutPadding().encodeToString(random),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
                catch(FileAlreadyExistsException ignored){}
            }
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>128)throw new java.io.IOException("Invalid registration key file");
            var acl=Files.getFileAttributeView(file,AclFileAttributeView.class);
            if(acl!=null)acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            else if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            String value=Files.readString(file,StandardCharsets.UTF_8).strip();
            if(!value.matches("[A-Za-z0-9_-]{43}"))throw new java.io.IOException("Invalid registration key format");
            expected=value.getBytes(StandardCharsets.UTF_8);
        }catch(java.io.IOException error){throw new IllegalStateException("无法初始化本机注册凭据",error);}
    }
    public boolean matches(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{43}")&&MessageDigest.isEqual(expected,value.getBytes(StandardCharsets.UTF_8));}
}
