package com.agentops.model;

/** Decode only a root JSON string property; incomplete escapes never appear in the UI. */
final class AnswerPrefix {
    private AnswerPrefix(){}
    private record Quoted(String value,int end,boolean complete){}
    static String read(String json){
        int at=space(json,0);if(at>=json.length()||json.charAt(at++)!='{')return "";
        while(true){at=space(json,at);if(at>=json.length()||json.charAt(at)!='"')return "";
            var key=quoted(json,at);if(!key.complete())return "";at=space(json,key.end());
            if(at>=json.length()||json.charAt(at++)!=':')return "";at=space(json,at);
            if("answer".equals(key.value()))return at<json.length()&&json.charAt(at)=='"'?quoted(json,at).value():"";
            at=skipValue(json,at);if(at<0)return "";at=space(json,at);if(at>=json.length()||json.charAt(at++)!=',')return "";
        }
    }
    private static int space(String s,int at){while(at<s.length()&&Character.isWhitespace(s.charAt(at)))at++;return at;}
    private static Quoted quoted(String s,int at){
        var value=new StringBuilder();at++;
        while(at<s.length()){char c=s.charAt(at++);if(c=='"')return new Quoted(safe(value),at,true);
            if(c=='\\'){if(at>=s.length())break;char escaped=s.charAt(at++);switch(escaped){
                case '"','\\','/'->value.append(escaped);case 'b'->value.append('\b');case 'f'->value.append('\f');case 'n'->value.append('\n');case 'r'->value.append('\r');case 't'->value.append('\t');
                case 'u'->{if(at+4>s.length())return new Quoted(safe(value),at,false);try{value.append((char)Integer.parseInt(s.substring(at,at+4),16));}catch(NumberFormatException e){return new Quoted(safe(value),at,false);}at+=4;}
                default->{return new Quoted(safe(value),at,false);}
            }}else if(c<32)return new Quoted(safe(value),at,false);else value.append(c);
        }return new Quoted(safe(value),at,false);
    }
    private static String safe(StringBuilder value){int end=value.length();if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
    private static int skipValue(String s,int at){
        if(at>=s.length())return -1;if(s.charAt(at)=='"'){var q=quoted(s,at);return q.complete()?q.end():-1;}
        int depth=0;boolean string=false,escape=false;
        for(int i=at;i<s.length();i++){char c=s.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;continue;}if(c=='"')string=true;else if(c=='{'||c=='[')depth++;else if(c=='}'||c==']'){if(depth==0)return i;if(--depth==0)return i+1;}else if(c==','&&depth==0)return i;}
        return -1;
    }
}
