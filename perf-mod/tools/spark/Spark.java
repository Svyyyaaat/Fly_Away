import me.lucko.spark.proto.SparkSamplerProtos.*;
import java.util.*;
import java.nio.file.*;
public class Spark {
  static List<StackTraceNode> nodes; static Map<String,String> cls, meth;
  static Map<String,Double> self=new HashMap<>(), incl=new HashMap<>(), modSelf=new HashMap<>(), modIncl=new HashMap<>();
  static double sum(List<Double> l){double s=0;for(double d:l)s+=d;return s;}
  static String key(StackTraceNode n){return n.getClassName()+"."+n.getMethodName();}
  static String mod(StackTraceNode n){String m=meth.get(n.getClassName()+"."+n.getMethodName()+n.getMethodDesc()); if(m==null) m=cls.get(n.getClassName()); return m==null?"(mc/jdk)":m;}
  static void walk(StackTraceNode n, Set<String> onPath, Set<String> modPath){
    double t=sum(n.getTimesList()); String k=key(n); String md=mod(n);
    boolean added=onPath.add(k); if(added) incl.merge(k,t,Double::sum);
    boolean ma=modPath.add(md); if(ma) modIncl.merge(md,t,Double::sum);
    double c=0; for(int r:n.getChildrenRefsList()){StackTraceNode ch=nodes.get(r); c+=sum(ch.getTimesList()); walk(ch,onPath,modPath);}
    self.merge(k,t-c,Double::sum); modSelf.merge(md,t-c,Double::sum);
    if(added) onPath.remove(k); if(ma) modPath.remove(md);
  }
  public static void main(String[] a) throws Exception {
    SamplerData d=SamplerData.parseFrom(Files.readAllBytes(Path.of(a[0])));
    cls=d.getClassSourcesMap(); meth=d.getMethodSourcesMap();
    System.out.println(d.getMetadata());
    String filter=a.length>1?a[1]:null; int top=a.length>2?Integer.parseInt(a[2]):60;
    for(ThreadNode th:d.getThreadsList()){
      double tt=sum(th.getTimesList()); System.out.printf("THREAD %-50s %.0f ms%n",th.getName(),tt);
    }
    for(ThreadNode th:d.getThreadsList()){
      if(filter!=null && !th.getName().contains(filter)) continue;
      nodes=th.getChildrenList(); self.clear();incl.clear();modSelf.clear();modIncl.clear();
      double tt=sum(th.getTimesList());
      for(int r:th.getChildrenRefsList()) walk(nodes.get(r),new HashSet<>(),new HashSet<>());
      System.out.println("\n===== "+th.getName()+" total "+tt);
      System.out.println("-- mod self"); modSelf.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(40).forEach(e->System.out.printf("%6.2f%% %s%n",100*e.getValue()/tt,e.getKey()));
      System.out.println("-- mod incl"); modIncl.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(40).forEach(e->System.out.printf("%6.2f%% %s%n",100*e.getValue()/tt,e.getKey()));
      System.out.println("-- self"); self.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(top).forEach(e->System.out.printf("%6.2f%% %s%n",100*e.getValue()/tt,e.getKey()));
      System.out.println("-- incl"); incl.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(top*3).forEach(e->System.out.printf("%6.2f%% %s%n",100*e.getValue()/tt,e.getKey()));
    }
  }
}
