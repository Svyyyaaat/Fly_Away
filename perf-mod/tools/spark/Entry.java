import me.lucko.spark.proto.SparkSamplerProtos.*;
import java.util.*;
import java.nio.file.*;
// usage: Entry prof regex [n] [upDepth]: for each node whose class.method matches regex and whose parent does NOT match,
// attribute the node's inclusive time to the caller chain (upDepth frames, default 1). Non-recursive counting.
public class Entry {
  static List<StackTraceNode> nodes; static java.util.regex.Pattern pat; static int up; static Map<String,Double> agg=new HashMap<>(); static double total;
  static double sum(List<Double> l){double s=0;for(double d:l)s+=d;return s;}
  static void walk(StackTraceNode n, Deque<String> path, boolean parentMatch){
    String km=n.getClassName()+"."+n.getMethodName();
    boolean m=pat.matcher(km).find();
    if(m && !parentMatch){
      double t=sum(n.getTimesList()); total+=t;
      StringBuilder sb=new StringBuilder(km);
      List<String> l=new ArrayList<>(path); for(int j=l.size()-1,i=0;j>=0&&i<up;j--,i++) sb.append("  <- ").append(l.get(j));
      agg.merge(sb.toString(),t,Double::sum);
      return; // don't descend: nested matches are inside this
    }
    path.addLast(km+":"+n.getLineNumber());
    for(int r:n.getChildrenRefsList()) walk(nodes.get(r),path,m);
    path.removeLast();
  }
  public static void main(String[] a) throws Exception {
    SamplerData d=SamplerData.parseFrom(Files.readAllBytes(Path.of(a[0]))); pat=java.util.regex.Pattern.compile(a[1]);
    int n=a.length>2?Integer.parseInt(a[2]):30; up=a.length>3?Integer.parseInt(a[3]):1;
    String tn=System.getProperty("thread","Render thread"); ThreadNode th=d.getThreadsList().stream().filter(t->t.getName().contains(tn)).findFirst().orElse(d.getThreadsList().get(0)); nodes=th.getChildrenList(); double tt=sum(th.getTimesList());
    for(int r:th.getChildrenRefsList()) walk(nodes.get(r),new ArrayDeque<>(),false);
    System.out.printf("TOTAL %.2f%%%n",100*total/tt);
    agg.entrySet().stream().sorted((x,y)->Double.compare(y.getValue(),x.getValue())).limit(n).forEach(e->System.out.printf("%6.2f%% %s%n",100*e.getValue()/tt,e.getKey()));
  }
}
