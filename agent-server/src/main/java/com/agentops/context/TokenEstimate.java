package com.agentops.context;

/** Conservative local estimate for context selection; provider usage remains authoritative for billing. */
public final class TokenEstimate {
    private TokenEstimate() {}
    public static int of(String text) {
        if(text==null || text.isEmpty()) return 0;
        int cjk=0;
        for(int i=0;i<text.length();i++) if(text.charAt(i)>=0x4E00 && text.charAt(i)<=0x9FFF) cjk++;
        return cjk+(text.length()-cjk+3)/4;
    }
}
