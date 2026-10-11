import me.lucko.spark.proto.SparkSamplerProtos.*;
import java.util.*;
import java.nio.file.*;
// Tree prof thresholdPct [rootContains]  ; collapses single-child chains of wrapper frames
public class Tree {
  static List<StackTraceNode> nodes; static double tt, thr;
  static double sum(List<Double> l){double s=0;for(double d:l)s+=d;return s;}
  static String nm(StackTraceNode n){return (n.getClassName()+"."+n.getMethodName()).replaceAll("\\$\\$Lambda/0x[0-9a-f]+","λ")+":"+n.getLineNumber();}
  static void print(StackTraceNode n,int ind){
    double t=sum(n.getTimesList()); if(100*t/tt<thr) return;
    List<StackTraceNode> ch=new ArrayList<>(); for(int r:n.getChildrenRefsList()) ch.add(nodes.get(r));
    // collapse chain
    StackTraceNode cur=n; int skipped=0;
    while(true){ List<StackTraceNode> c=new ArrayList<>(); for(int r:cur.getChildrenRefsList()) c.add(nodes.get(r));
      c.removeIf(x->100*sum(x.getTimesList())/tt<thr);
      if(c.size()==1 && Math.abs(sum(c.get(0).getTimesList())-sum(cur.getTimesList()))<tt*thr/100/4){cur=c.get(0);skipped++;} else break; }
    System.out.printf("%s%5.2f%% %s%s%n","  ".repeat(ind),100*t/tt,nm(n),skipped>0?(" ..("+skipped+").. "+nm(cur)):"");
    List<StackTraceNode> c=new ArrayList<>(); for(int r:cur.getChildrenRefsList()) c.add(nodes.get(r));
    c.sort((x,y)->Double.compare(sum(y.getTimesList()),sum(x.getTimesList())));
    for(StackTraceNode x:c) print(x,ind+1);
  }
  static void find(StackTraceNode n,String root){ if(nm(n).contains(root)){print(n,0);return;} for(int r:n.getChildrenRefsList()) find(nodes.get(r),root);}
  public static void main(String[] a) throws Exception {
    SamplerData d=SamplerData.parseFrom(Files.readAllBytes(Path.of(a[0]))); thr=Double.parseDouble(a[1]);
    String tn=System.getProperty("thread","Render thread"); ThreadNode th=d.getThreadsList().stream().filter(t->t.getName().contains(tn)).findFirst().orElse(d.getThreadsList().get(0)); nodes=th.getChildrenList(); tt=sum(th.getTimesList());
    for(int r:th.getChildrenRefsList()) { if(a.length>2) find(nodes.get(r),a[2]); else print(nodes.get(r),0);}
  }
}
