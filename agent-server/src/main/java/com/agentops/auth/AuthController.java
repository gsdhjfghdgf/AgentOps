package com.agentops.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;private final RegistrationKey key;
    public AuthController(AuthService service,RegistrationKey key){this.service=service;this.key=key;}
    public record Register(@NotBlank @Size(max=32)String username,@NotBlank @Size(max=48)String displayName,@NotBlank @Size(min=10,max=128)String password,boolean inheritLegacy,@NotBlank @Size(max=128)String registrationKey){@Override public String toString(){return "Register[redacted]";}}
    public record Login(@NotBlank @Size(max=32)String username,@NotBlank @Size(max=128)String password,boolean remember){@Override public String toString(){return "Login[redacted]";}}
    public record Profile(@NotBlank @Size(max=48)String displayName){}
    public record Password(@NotBlank @Size(max=128)String currentPassword,@NotBlank @Size(min=10,max=128)String newPassword){@Override public String toString(){return "PasswordChange[redacted]";}}
    @GetMapping("/bootstrap") public Object bootstrap(){return Map.of("setupRequired",service.setupRequired());}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Object register(@Valid @RequestBody Register input,HttpServletRequest request){
        if(!key.matches(input.registrationKey()))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"注册凭据无效，请从本机 AgentOps 桌面端创建账号");
        return Map.of("account",service.register(input.username(),input.displayName(),input.password(),input.inheritLegacy(),loopback(request.getRemoteAddr()),request.getRemoteAddr()));
    }
    @PostMapping("/login") public Object login(@Valid @RequestBody Login input,HttpServletRequest request){return service.login(input.username(),input.password(),input.remember(),request.getHeader("User-Agent"),request.getRemoteAddr());}
    @GetMapping("/me") public Object me(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account){return Map.of("account",account.account());}
    @PutMapping("/me") public Object profile(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account,@Valid @RequestBody Profile input){return Map.of("account",service.rename(account,input.displayName()));}
    @PutMapping("/password") public Object password(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account,@Valid @RequestBody Password input){service.changePassword(account,input.currentPassword(),input.newPassword());return Map.of("ok",true);}
    @PostMapping("/logout") public Object logout(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account){service.logout(account);return Map.of("ok",true);}
    @PostMapping("/logout-all") public Object logoutAll(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account){service.logoutAll(account);return Map.of("ok",true);}
    @GetMapping("/sessions") public Object sessions(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account){return Map.of("sessions",service.sessions(account));}
    @DeleteMapping("/sessions/{id}") public Object revoke(@RequestAttribute(AuthFilter.ACCOUNT)AuthService.Authenticated account,@PathVariable UUID id){service.revoke(account,id);return Map.of("ok",true);}
    private static boolean loopback(String address){return "127.0.0.1".equals(address)||"::1".equals(address)||"0:0:0:0:0:0:0:1".equals(address);}
}
