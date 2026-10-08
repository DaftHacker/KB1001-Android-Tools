import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import com.dafthacker.kb1001perf.CpuOppDtbPatcher;

/** Host-side DTB patch verification CLI; never flashes an image. */
public final class CpuOppDtbCli {
    private static String hex(byte[] bytes){
        StringBuilder s=new StringBuilder();
        for(byte x:bytes)s.append(String.format("%02x",x&0xff));
        return s.toString();
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4 || !(args[0].equals("unlock") || args[0].equals("lock"))) {
            System.err.println("Usage: java CpuOppDtbCli <unlock|lock> <approved_mhz> <input.dtb> <output.dtb>");
            System.exit(2);
        }
        int mhz=Integer.parseInt(args[1]);
        Path in=Paths.get(args[2]), out=Paths.get(args[3]);
        if(in.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Input and output must differ");
        byte[] original=Files.readAllBytes(in);
        byte[] result=CpuOppDtbPatcher.patch(original,mhz,args[0].equals("unlock"));
        Files.write(out,result);
        System.out.println("DTB_PATCH_PASS");
        System.out.println("frequency_mhz="+mhz);
        System.out.println("requested_state="+args[0]);
        System.out.println("source_sha256="+hex(MessageDigest.getInstance("SHA-256").digest(original)));
        System.out.println("candidate_sha256="+hex(MessageDigest.getInstance("SHA-256").digest(result)));
        System.out.println("NO_FLASH_PERFORMED");
    }
}