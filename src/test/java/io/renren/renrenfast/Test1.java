package io.renren.renrenfast;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class Test1 {

    public void testIndexof() {
        String str1="abcdef";
        String str2="gh";
        System.out.println( str1.indexOf(str2) );

        String str3="abcdef0abcdef";
        String str4="gh";
        System.out.println( str3.indexOf(str4,5) );
    }

    public static void testLamda() {
        List lists=new LinkedList();
        lists.add("a");
        lists.add("b");
        lists.forEach(obj->System.out.println("显示： "+obj));

        System.out.println("---------lamda表达式遍历和Iterator遍历同等效应-------------");

        List list1=new LinkedList();
        list1.add("a");
        list1.add("b");
        Iterator it=list1.iterator();
        while(it.hasNext()){
            String list=(String)it.next();
            System.out.println(list);
        }
    }

    public static void main(String[] args) {
        testLamda();
    }
}
