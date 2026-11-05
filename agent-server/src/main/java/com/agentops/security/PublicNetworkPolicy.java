package com.agentops.security;

import java.net.*;
import java.io.IOException;
import java.util.Set;

public final class PublicNetworkPolicy {
    private PublicNetworkPolicy(){}
    public static URI uri(String text){
        if(text==null||text.length()>2048||text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("URL无效");
        URI uri;try{uri=URI.create(text);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("URL无效");}
        if(uri.getScheme()==null||!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=80&&uri.getPort()!=443)throw new IllegalArgumentException("只支持公网 HTTP/HTTPS 页面和80/443端口");
        return uri;
    }
    public static InetAddress resolve(URI uri)throws IOException{
        var addresses=InetAddress.getAllByName(uri.getHost());
        if(addresses.length==0)throw new IOException("域名未解析到有效地址");
        for(var address:addresses)requirePublic(address);
        return addresses[0];
    }
    public static void requirePublic(InetAddress ip){
        byte[] b=ip.getAddress();int first=b[0]&255,second=b[1]&255;
        boolean blocked=ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress();
        if(b.length==4)blocked|=first==0||first>=224||first==100&&second>=64&&second<=127||first==198&&(second==18||second==19)||first==192&&second==0||first==198&&second==51&&(b[2]&255)==100||first==203&&second==0&&(b[2]&255)==113;
        else blocked|=(first&0xe0)!=0x20||first==0x20&&second==2||first==0x20&&second==1&&((b[2]==0&&b[3]==0)||((b[2]&255)==0x0d&&(b[3]&255)==0xb8));
        if(blocked)throw new IllegalArgumentException("联网工具不访问本机、内网、保留或元数据地址");
    }
}
