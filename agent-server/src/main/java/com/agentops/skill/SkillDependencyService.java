package com.agentops.skill;
import com.agentops.domain.Primitives.Principal;
import com.agentops.runtime.RuntimeService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;
@Service
public class SkillDependencyService {
    private final SkillService skills;private final RuntimeService runtimes;
    public SkillDependencyService(SkillService skills,RuntimeService runtimes){this.skills=skills;this.runtimes=runtimes;}
    public Map<String,Object> check(Principal p,String id){
        var skill=skills.catalog(p).stream().filter(s->id.equals(s.get("id"))||id.equals(s.get("name"))).findFirst().orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("技能不存在"));
        String text=skill.get("content").toString();var requirements=requirements(text);var rows=new ArrayList<Map<String,Object>>();
        for(var entry:requirements.entrySet()){
            String kind=entry.getKey();int required=entry.getValue();var probe=runtimes.probe(kind);boolean present=Boolean.TRUE.equals(probe.get("installed"));String version=probe.get("version").toString();
            int actual=0;var matcher=Pattern.compile("(?i)(?:v|python\\s+)?(\\d+)\\.").matcher(version);if(matcher.find())actual=Integer.parseInt(matcher.group(1));
            String minimum=minimumVersion(text,kind);String status=!present?"missing":!minimum.isBlank()&&!satisfies(version,minimum)?"incompatible":"ready";
            rows.add(Map.of("kind",kind,"requiredMajor",required,"required",!minimum.isBlank()?">="+minimum:"说明未指定版本","current",version,"status",status,"managed",probe.get("managed")));
        }
        String status=rows.stream().anyMatch(r->!r.get("status").equals("ready"))?"needs_setup":requirements.isEmpty()&&text.contains("scripts/")?"unknown":"ready";
        return Map.of("id",skill.get("id"),"name",skill.get("name"),"installed",true,"enabled",skill.get("enabled"),"status",status,"dependencies",rows,
                "note","根据技能说明检测运行依赖，不执行技能脚本。检查通过表示运行时存在，不等于技能业务流程已验证。");
    }
    public static Map<String,Integer> requirements(String text){
        var result=new LinkedHashMap<String,Integer>();String lower=text.toLowerCase(Locale.ROOT);
        for(String kind:List.of("node","python")){
            var pattern=Pattern.compile("(?i)\\b"+kind+"(?:\\.js)?\\s*(?:version\\s*)?(?:>=|≥|v|版本)?\\s*(\\d{1,2})(?:\\.\\d+)*\\s*\\+?");var matcher=pattern.matcher(text);
            int major=0;while(matcher.find())major=Math.max(major,Integer.parseInt(matcher.group(1)));
            if(major>0||lower.contains(kind)||kind.equals("node")&&lower.contains(".mjs")||kind.equals("python")&&lower.contains(".py"))result.put(kind,major);
        }
        if(lower.contains("chromium")||lower.contains("chrome")||lower.contains("microsoft edge"))result.put("browser",0);
        return result;
    }
    private static String minimumVersion(String text,String kind){if(kind.equals("browser"))return "";var m=Pattern.compile("(?i)\\b"+kind+"(?:\\.js)?\\s*(?:version\\s*)?(?:>=|≥|v|版本|\\^|~)?\\s*(\\d{1,2}(?:\\.\\d+){0,2})").matcher(text);String best="";while(m.find())if(best.isBlank()||!satisfies(best,m.group(1)))best=m.group(1);return best;}
    private static boolean satisfies(String actual,String minimum){var m=Pattern.compile("(\\d+(?:\\.\\d+){0,2})").matcher(actual);if(!m.find())return false;String[] a=m.group(1).split("\\."),b=minimum.split("\\.");for(int i=0;i<3;i++){int left=i<a.length?Integer.parseInt(a[i]):0,right=i<b.length?Integer.parseInt(b[i]):0;if(left!=right)return left>right;}return true;}
}
