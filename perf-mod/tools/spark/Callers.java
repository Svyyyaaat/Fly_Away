import me.lucko.spark.proto.SparkSamplerProtos.*;
import java.util.*;
import java.nio.file.*;
// usage: Callers prof target depth  -> aggregates stacks (callers chain of depth) for nodes whose class.method contains target
public class Callers {
  static List<StackTraceNode> nodes; static String target; static int depth; static Map<String,Double> agg=new HashMap<>(); static double total;
  static double sum(List<Double> l){double s=0;for(double d:l)s+=d;return s;}
  static void walk(StackTraceNode n, Deque<String> path){
    String k=n.getClassName()+"."+n.getMethodName()+":"+n.getLineNumber();
    if((n.getClassName()+"."+n.getMethodName()).contains(target)){
      double t=sum(n.getTimesList()); total+=t;
      StringBuilder sb=new StringBuilder(); Iterator<String> it=path.descendingIterator(); int i=0;
      List<String> l=new ArrayList<>(path); for(int j=l.size()-1;j>=0&&i<depth;j--,i++) sb.append("\n      <- ").append(l.get(j));
      agg.merge(sb.toString(),t,Double::sum); return;
    }
    path.addLast(k.replaceAll("\\$\\$Lambda.*","λ"));
    for(int r:n.getChildrenRefsList()) walk(nodes.get(r),path);
    path.removeLast();
  }
  public static void main(String[] a) throws Exception {
    SamplerData d=SamplerData.parseFrom(Files.readAllBytes(Path.of(a[0]))); target=a[1]; depth=Integer.parseInt(a[2]);
    ThreadNode th=d.getThreadsList().get(0); nodes=th.getChildrenList(); double tt=sum(th.getTimesList());
    for(int r:th.getChildrenRefsList()) walk(nodes.get(r),new ArrayDeque<>());
    System.out.printf("TOTAL %.2f%%%n",100*total/tt);
    agg.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(a.length>3?Integer.parseInt(a[3]):15).forEach(e->System.out.printf("%6.2f%%%s%n",100*e.getValue()/tt,e.getKey()));
  }
}
