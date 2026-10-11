import me.lucko.spark.proto.SparkSamplerProtos.*;
import java.util.*;
import java.nio.file.*;
// usage: Both <profileA> <profileB>  -> every Render-thread method with >=0.15% self or >=0.5% inclusive in either
// profile, with self/inclusive % for both (A/B flags say which profile hits the threshold). Non-recursive inclusive.
public class Both {
  static List<StackTraceNode> nodes;
  static double sum(List<Double> l){double s=0;for(double d:l)s+=d;return s;}
  static String key(StackTraceNode n){return n.getClassName()+"."+n.getMethodName();}
  static void walk(StackTraceNode n, Set<String> onPath, Map<String,Double> self, Map<String,Double> incl){
    double t=sum(n.getTimesList()); String k=key(n);
    boolean added=onPath.add(k); if(added) incl.merge(k,t,Double::sum);
    double c=0; for(int r:n.getChildrenRefsList()){StackTraceNode ch=nodes.get(r); c+=sum(ch.getTimesList()); walk(ch,onPath,self,incl);}
    self.merge(k,t-c,Double::sum);
    if(added) onPath.remove(k);
  }
  static Map<String,double[]> load(String p) throws Exception {
    SamplerData d=SamplerData.parseFrom(Files.readAllBytes(Path.of(p)));
    Map<String,double[]> out=new HashMap<>();
    for(ThreadNode th:d.getThreadsList()){
      if(!th.getName().equals("Render thread")) continue;
      nodes=th.getChildrenList(); double tt=sum(th.getTimesList());
      Map<String,Double> self=new HashMap<>(), incl=new HashMap<>();
      for(int r:th.getChildrenRefsList()) walk(nodes.get(r),new HashSet<>(),self,incl);
      for(var e:incl.entrySet()) out.put(e.getKey(), new double[]{100*self.getOrDefault(e.getKey(),0.0)/tt, 100*e.getValue()/tt});
    }
    return out;
  }
  public static void main(String[] a) throws Exception {
    Map<String,double[]> A=load(a[0]), B=load(a[1]);
    Set<String> keys=new TreeSet<>(); keys.addAll(A.keySet()); keys.addAll(B.keySet());
    List<String> rows=new ArrayList<>();
    double[] z={0,0};
    for(String k:keys){ double[] x=A.getOrDefault(k,z), y=B.getOrDefault(k,z);
      boolean hitA = x[0]>=0.15||x[1]>=0.5, hitB=y[0]>=0.15||y[1]>=0.5;
      if(hitA||hitB) rows.add(String.format("%s%s selfA %5.2f inclA %6.2f | selfB %5.2f inclB %6.2f  %s", hitA?"A":"-", hitB?"B":"-", x[0],x[1],y[0],y[1],k));
    }
    rows.sort(Comparator.comparing((String s)->s.substring(0,2)).reversed().thenComparing(s->s));
    rows.forEach(System.out::println);
  }
}
