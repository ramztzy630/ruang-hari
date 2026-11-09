import java.util.*;
import com.ruanghari.app.ShufflePolicy;
public class ShufflePolicyCheck {
 public static void main(String[] args){
  if(ShufflePolicy.pickIndex(Collections.emptyList(),"a")!=-1)throw new AssertionError();
  if(ShufflePolicy.pickIndex(List.of("a"),"a")!=0)throw new AssertionError();
  for(int n=0;n<1000;n++){
   int index=ShufflePolicy.pickIndex(List.of("a","b","c"),"b");
   if(index<0||index>2||index==1)throw new AssertionError("Repeated current track");
  }
  System.out.println("PASS: native shuffle empty/single/multiple queues, excludes current track");
 }
}
