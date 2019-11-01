package io.renren.renrenfast;

public class Test1 {
    public static void main(String[] args) {
        String str1="abcdef";
        String str2="gh";
        System.out.println( str1.indexOf(str2) );

        String str3="abcdef0abcdef";
        String str4="gh";
        System.out.println( str3.indexOf(str4,5) );
    }
}
