package com.agentops.security;

import java.util.*;

/** Do not pass service passwords, API tokens or interpreter startup hooks into subprocesses. */
public final class ProcessEnvironment {
    private static final Set<String> ALLOWED=Set.of("path","pathext","systemroot","windir","systemdrive","comspec","temp","tmp","tmpdir","home","userprofile","appdata","localappdata","programfiles","programfiles(x86)","programw6432","commonprogramfiles","commonprogramfiles(x86)","allusersprofile","programdata","processor_architecture","number_of_processors","lang","lc_all","lc_ctype","os");
    private ProcessEnvironment(){}
    public static void sanitize(ProcessBuilder builder){builder.environment().entrySet().removeIf(e->!ALLOWED.contains(e.getKey().toLowerCase(Locale.ROOT)));}
    /** Libraries that merge env overrides need empty values for excluded parent variables. */
    public static Map<String,String> overrides(){var result=new HashMap<String,String>();System.getenv().forEach((k,v)->result.put(k,ALLOWED.contains(k.toLowerCase(Locale.ROOT))?v:""));return result;}
}
