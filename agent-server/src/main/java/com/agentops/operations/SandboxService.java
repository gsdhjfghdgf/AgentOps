package com.agentops.operations;

import com.agentops.model.ModelPort.ExecutionControl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** VM-side Docker, no host mounts, no network, no automatic fallback to the host terminal. */
@Service
public class SandboxService {
    private final String target,key;private final int port;
    public SandboxService(@Value("${agent.sandbox.ssh-target:zww@127.0.0.1}")String target,@Value("${agent.sandbox.ssh-key:D:/VM/scripts/vm_key}")String key,@Value("${agent.sandbox.ssh-port:2222}")int port){this.target=target;this.key=key;this.port=port;}
    private List<String> ssh(String command){return List.of("ssh","-p",Integer.toString(port),"-i",key,"-o","BatchMode=yes","-o","StrictHostKeyChecking=yes","-o","ConnectTimeout=8",target,command);}
    public Map<String,Object> status(){return Map.of("mode","vm-docker","hostMounted",false,"network",false,"sshKeyAvailable",Files.isRegularFile(Path.of(key)),"image","node:22-bookworm-slim","note","使用虚拟机内预先安装的镜像；未挂载用户项目，临时目录修改随容器销毁。主机command_run仍为系统用户权限。");}
    public Map<String,Object> run(String command,int seconds,ExecutionControl control)throws Exception{
        if(command==null||command.isBlank()||command.length()>12000||seconds<1||seconds>120)throw new IllegalArgumentException("命令不能为空，超时须为1～120秒");
        if(control.cancellationRequested()||control.exceeded(Instant.now()))throw new IllegalStateException("任务已停止");
        String name="agentops-"+UUID.randomUUID();
        // Send script bytes through stdin. Windows OpenSSH command-line quoting must never
        // reinterpret user quotes, dollar signs or command substitutions on the VM host.
        String script="docker run --rm -i --pull=never --name "+name+" --network none --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 64 --memory 256m --cpus 1 --user 65534:65534 --tmpfs /tmp:rw,nosuid,nodev,size=64m --workdir /tmp node:22-bookworm-slim sh -s";
        var process=new ProcessBuilder(ssh(script)).redirectErrorStream(true).start();var reader=Executors.newSingleThreadExecutor();var output=reader.submit(()->{try(var in=process.getInputStream()){var result=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;while((read=in.read(buffer))!=-1)if(result.size()<20000)result.write(buffer,0,Math.min(read,20000-result.size()));return result.toString(StandardCharsets.UTF_8);}});
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);boolean stopped=false;
        try{try(var input=process.getOutputStream()){input.write((command+"\n").getBytes(StandardCharsets.UTF_8));}
            while(!process.waitFor(150,TimeUnit.MILLISECONDS))if(control.cancellationRequested()||control.exceeded(Instant.now())||System.nanoTime()>end){stopped=true;break;}
            if(stopped){process.destroyForcibly();throw new IllegalStateException("隔离命令已取消或超时");}
            return Map.of("exitCode",process.exitValue(),"output",output.get(2,TimeUnit.SECONDS),"isolated",true,"network",false,"projectMounted",false);
        }finally{
            process.destroyForcibly();reader.shutdownNow();
            // Killing SSH alone does not stop a detached server-side container.
            var cleanup=new ProcessBuilder(ssh("docker rm -f "+name+" >/dev/null 2>&1 || true")).start();if(!cleanup.waitFor(10,TimeUnit.SECONDS))cleanup.destroyForcibly();
        }
    }
}
