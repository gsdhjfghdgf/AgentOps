package com.agentops.tool;
import java.math.*;

/** 有界四则运算解析器，不使用脚本引擎或eval。 */
public final class SafeCalculator {
    private final String text;private int at,depth;
    private static final MathContext MC=MathContext.DECIMAL128;
    private SafeCalculator(String text){this.text=text;}
    public static String evaluate(String expression) {
        if(expression==null || expression.isBlank() || expression.length()>512)throw new IllegalArgumentException("算式须为1～512字符");
        var parser=new SafeCalculator(expression.replaceAll("\\s+", ""));
        try { var result=parser.expression();if(parser.at!=parser.text.length())throw new IllegalArgumentException("算式包含不支持的字符");return result.stripTrailingZeros().toPlainString(); }
        catch(ArithmeticException e){throw new IllegalArgumentException("算式无法计算：除零或数值超限");}
    }
    private BigDecimal expression() {
        if(++depth>40)throw new IllegalArgumentException("括号嵌套过深");
        BigDecimal result=term();
        while(at<text.length() && (text.charAt(at)=='+' || text.charAt(at)=='-')) {char op=text.charAt(at++);var next=term();result=op=='+'?result.add(next,MC):result.subtract(next,MC);}
        depth--;return result;
    }
    private BigDecimal term() {
        BigDecimal result=factor();
        while(at<text.length() && "*/%".indexOf(text.charAt(at))>=0){char op=text.charAt(at++);var next=factor();result=switch(op){case '*'->result.multiply(next,MC);case '/'->result.divide(next,MC);default->result.remainder(next,MC);};}
        return result;
    }
    private BigDecimal factor() {
        if(at>=text.length())throw new IllegalArgumentException("算式不完整");
        char c=text.charAt(at);
        if(c=='+' || c=='-'){at++;if(++depth>40)throw new IllegalArgumentException("算式嵌套过深");var value=factor();depth--;return c=='-'?value.negate():value;}
        if(c=='('){at++;var value=expression();if(at>=text.length() || text.charAt(at++)!=')')throw new IllegalArgumentException("括号不匹配");return value;}
        int start=at;while(at<text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at)=='.'))at++;
        if(start==at || at-start>128)throw new IllegalArgumentException("仅支持数字、括号和 + - * / %");
        try{return new BigDecimal(text.substring(start,at),MC);}catch(NumberFormatException e){throw new IllegalArgumentException("数字格式不正确");}
    }
}
