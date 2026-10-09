import java.nio.file.*;
import java.util.Arrays;
import com.dafthacker.kb1001perf.CpuOppDtbPatcher;

public class CpuOppDtbPatcherTest {
    private static int assertions=0;
    static void check(boolean yes,String msg){assertions++;if(!yes)throw new AssertionError(msg);}
    static void rejected(Runnable r,String msg){boolean did=false;try{r.run();}catch(IllegalArgumentException ex){did=true;}check(did,msg);}
    public static void main(String[] args)throws Exception{
        byte[] original=Files.readAllBytes(Paths.get(args[0]));
        int[] approved=CpuOppDtbPatcher.approvedFrequenciesMhz();
        check(approved.length==9,"approved count");
        for(int mhz:approved){
            CpuOppDtbPatcher.OppState initial=CpuOppDtbPatcher.inspect(original,mhz);
            int expected=mhz==1296?1100000:1150000;
            check(initial.microvolts==0||initial.microvolts==expected,"initial voltage "+mhz);
            byte[] enabled=CpuOppDtbPatcher.patch(original,mhz,true);
            CpuOppDtbPatcher.OppState enabledState=CpuOppDtbPatcher.inspect(enabled,mhz);
            check(enabledState.enabled && enabledState.turbo && enabledState.microvolts==expected,"enable "+mhz);
            check(Arrays.equals(enabled,CpuOppDtbPatcher.patch(enabled,mhz,true)),"enable idempotent "+mhz);
            byte[] disabled=CpuOppDtbPatcher.patch(enabled,mhz,false);
            CpuOppDtbPatcher.OppState disabledState=CpuOppDtbPatcher.inspect(disabled,mhz);
            check(!disabledState.enabled && !disabledState.turbo && disabledState.microvolts==0,"disable "+mhz);
            check(Arrays.equals(disabled,CpuOppDtbPatcher.patch(disabled,mhz,false)),"disable idempotent "+mhz);
        }
        rejected(()->CpuOppDtbPatcher.patch(original,1800,true),"1800 experimental refused");
        rejected(()->CpuOppDtbPatcher.patch(original,1944,true),"1944 experimental refused");
        byte[] corrupt=original.clone();corrupt[0]=0;
        rejected(()->CpuOppDtbPatcher.patch(corrupt,1296,false),"invalid FDT rejected");
        byte[] truncated=Arrays.copyOf(original,original.length-1);
        rejected(()->CpuOppDtbPatcher.patch(truncated,1296,false),"truncated FDT rejected");
        byte[] successive=original.clone();
        for(int mhz:approved)successive=CpuOppDtbPatcher.patch(successive,mhz,true);
        for(int mhz:approved)check(CpuOppDtbPatcher.inspect(successive,mhz).enabled,"sequential independent unlock "+mhz);
        successive=CpuOppDtbPatcher.patch(successive,1512,false);
        check(!CpuOppDtbPatcher.inspect(successive,1512).enabled,"lock one");
        for(int mhz:approved)if(mhz!=1512)check(CpuOppDtbPatcher.inspect(successive,mhz).enabled,"other OPP still unlocked "+mhz);
        // One firmware DTB candidate with a mixed nine-frequency configuration.
        byte[] mixed=CpuOppDtbPatcher.patchConfiguration(original,"101010101");
        for(int i=0;i<approved.length;i++)
            check(CpuOppDtbPatcher.inspect(mixed,approved[i]).enabled==(i%2==0),
                "combined state "+approved[i]);
        byte[] restored=CpuOppDtbPatcher.patchConfiguration(mixed,"111111111");
        for(int mhz:approved)
            check(CpuOppDtbPatcher.inspect(restored,mhz).enabled,"restore "+mhz);
        check(Arrays.equals(restored,CpuOppDtbPatcher.patchConfiguration(restored,"111111111")),
            "combined idempotent");
        rejected(()->CpuOppDtbPatcher.patchConfiguration(original,"000"),"short mask rejected");
        rejected(()->CpuOppDtbPatcher.patchConfiguration(original,"111111112"),"invalid mask rejected");
        System.out.println("PASS: "+assertions+" assertions; 9 isolated OPPs + combinations + corrupt images + experimental rejection");
    }
}
