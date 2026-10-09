package com.dafthacker.kb1001perf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fail-closed, hardware-specific flattened Device Tree (FDT) editor for the
 * KB1001 / Allwinner A333 VF0403 CPU OPP table.
 *
 * This edits an extracted DTB; it never flashes, unpacks or repacks an image.
 * Only OPPs that completed staged physical qualification are supported.
 */
public final class CpuOppDtbPatcher {
    private CpuOppDtbPatcher() {}

    private static final int FDT_MAGIC = 0xd00dfeed;
    private static final int FDT_BEGIN_NODE = 1;
    private static final int FDT_END_NODE = 2;
    private static final int FDT_PROP = 3;
    private static final int FDT_NOP = 4;
    private static final int FDT_END = 9;
    private static final int HEADER_SIZE = 40;
    private static final int MAX_DTB_BYTES = 4 * 1024 * 1024;

    public static final class Opp {
        public final String table;
        public final int mhz;
        public final int microvolts;
        private Opp(String table, int mhz, int microvolts) {
            this.table=table; this.mhz=mhz; this.microvolts=microvolts;
        }
        public String nodeName() { return "opp@" + ((long) mhz * 1000000L); }
    }

    // 1800 is deliberately excluded: Stage 15 has NOT yet passed B and C.
    private static final Opp[] APPROVED = {
        new Opp("cluster0-opp-table", 1296, 1100000),
        new Opp("cluster0-opp-table", 1344, 1150000),
        new Opp("cluster0-opp-table", 1368, 1150000),
        new Opp("cluster0-opp-table", 1416, 1150000),
        new Opp("cluster0-opp-table", 1464, 1150000),
        new Opp("cluster0-opp-table", 1512, 1150000),
        new Opp("cluster2-opp-table", 1560, 1150000),
        new Opp("cluster2-opp-table", 1608, 1150000),
        new Opp("cluster1-opp-table", 1776, 1150000)
    };

    public static int[] approvedFrequenciesMhz() {
        int[] out = new int[APPROVED.length];
        for (int i=0;i<APPROVED.length;i++) out[i]=APPROVED[i].mhz;
        return out;
    }
    private static Opp approved(int mhz) {
        for (Opp a: APPROVED) if (a.mhz==mhz) return a;
        throw new IllegalArgumentException("Frequency has not completed physical validation: " + mhz + " MHz");
    }
    public static final class OppState {
        public final int mhz;
        public final boolean enabled;
        public final int microvolts;
        public final boolean turbo;
        private OppState(int mhz, int uv, boolean turbo) {
            this.mhz=mhz; this.microvolts=uv; this.turbo=turbo;
            this.enabled=uv>0 && turbo;
        }
    }

    private abstract static class Entry {}
    private static final class Node extends Entry {
        final String name;
        final List<Entry> children=new ArrayList<>();
        Node(String name) { this.name=name; }
    }
    private static final class Prop extends Entry {
        final String name;
        byte[] data;
        Prop(String name, byte[] data) { this.name=name; this.data=data; }
    }
    private static final class Nop extends Entry {}

    private static final class Tree {
        byte[] original, strings;
        int structOffset, stringsOffset;
        Node root;
    }

    private static int readInt(byte[] b, int o) {
        if (o<0 || o>b.length-4) throw new IllegalArgumentException("FDT integer out of bounds");
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
    private static long readLong(byte[] b, int o) {
        if (o<0 || o>b.length-8) throw new IllegalArgumentException("FDT u64 out of bounds");
        return ByteBuffer.wrap(b, o, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }
    private static void putInt(byte[] b, int o, int n) {
        ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).putInt(n);
    }
    private static byte[] u32(int n) {
        byte[] b=new byte[4]; putInt(b,0,n); return b;
    }
    private static void emitInt(ByteArrayOutputStream out, int n) {
        byte[] b=u32(n); out.write(b,0,b.length);
    }
    private static void emitZ(ByteArrayOutputStream out, String s) {
        byte[] b=s.getBytes(StandardCharsets.US_ASCII);
        out.write(b,0,b.length); out.write(0);
        while ((out.size() & 3)!=0) out.write(0);
    }
    private static void emitPad(ByteArrayOutputStream out) {
        while ((out.size() & 3)!=0) out.write(0);
    }
    private static void require(boolean condition, String explanation) {
        if (!condition) throw new IllegalArgumentException("Invalid/unsupported FDT: " + explanation);
    }
    private static String stringAt(byte[] b, int start, int max, int absoluteOffset) {
        require(absoluteOffset>=start && absoluteOffset<max,"string offset");
        int end=absoluteOffset;
        while (end<max && b[end]!=0) end++;
        require(end<max,"unterminated string");
        return new String(b,absoluteOffset,end-absoluteOffset,StandardCharsets.US_ASCII);
    }
    private static Tree parse(byte[] source) {
        require(source!=null && source.length>=HEADER_SIZE && source.length<=MAX_DTB_BYTES,"DTB size");
        require(readInt(source,0)==FDT_MAGIC,"magic");
        int total=readInt(source,4);
        int os=readInt(source,8);
        int ostr=readInt(source,12);
        int omem=readInt(source,16);
        int vers=readInt(source,20);
        int compat=readInt(source,24);
        int lenStrings=readInt(source,32);
        int lenStruct=readInt(source,36);
        require(total==source.length,"total size");
        require(vers>=17 && compat<=17,"header version");
        require(os>=HEADER_SIZE && (os&3)==0,"structure offset");
        require(omem>=HEADER_SIZE && omem+16<=os,"reserve map");
        require(lenStruct>0 && (long)os+lenStruct<=total,"structure section");
        require(lenStrings>0 && ostr>=os+lenStruct && (long)ostr+lenStrings<=total,"strings section");
        int endStruct=os+lenStruct;
        int endStrings=ostr+lenStrings;
        Tree tree=new Tree();
        tree.original=source;
        tree.structOffset=os;
        tree.stringsOffset=ostr;
        tree.strings=Arrays.copyOfRange(source,ostr,endStrings);
        Node root=null;
        ArrayList<Node> stack=new ArrayList<>();
        int pos=os;
        boolean sawEnd=false;
        while (pos<=endStruct-4) {
            int token=readInt(source,pos); pos+=4;
            if (token==FDT_BEGIN_NODE) {
                int nameEnd=pos;
                while (nameEnd<endStruct && source[nameEnd]!=0) nameEnd++;
                require(nameEnd<endStruct,"unterminated node");
                String name=new String(source,pos,nameEnd-pos,StandardCharsets.US_ASCII);
                pos=(nameEnd+4)&~3;
                require(pos<=endStruct,"node padding");
                Node n=new Node(name);
                if (stack.isEmpty()) {
                    require(root==null,"multiple root nodes"); root=n;
                } else stack.get(stack.size()-1).children.add(n);
                stack.add(n);
            } else if (token==FDT_PROP) {
                require(!stack.isEmpty() && pos+8<=endStruct,"property outside node");
                int len=readInt(source,pos), nameOff=readInt(source,pos+4); pos+=8;
                require(len>=0 && (long)pos+len<=endStruct,"property length");
                String name=stringAt(source,ostr,endStrings,ostr+nameOff);
                stack.get(stack.size()-1).children.add(new Prop(name,Arrays.copyOfRange(source,pos,pos+len)));
                pos=(pos+len+3)&~3;
                require(pos<=endStruct,"property padding");
            } else if (token==FDT_END_NODE) {
                require(!stack.isEmpty(),"unpaired END_NODE"); stack.remove(stack.size()-1);
            } else if (token==FDT_NOP) {
                if (!stack.isEmpty()) stack.get(stack.size()-1).children.add(new Nop());
            } else if (token==FDT_END) {
                require(stack.isEmpty() && root!=null,"unclosed root");
                sawEnd=true; break;
            } else throw new IllegalArgumentException("Invalid FDT structure token: " + token);
        }
        require(sawEnd,"missing FDT_END");
        tree.root=root;
        return tree;
    }
    private static Prop property(Node node, String name) {
        Prop found=null;
        for (Entry e: node.children) if (e instanceof Prop && ((Prop)e).name.equals(name)) {
            require(found==null,"duplicate property " + name);
            found=(Prop)e;
        }
        return found;
    }
    private static Node locate(Tree tree, Opp spec) {
        ArrayList<Node> tables=new ArrayList<>();
        findTables(tree.root,spec.table,tables);
        require(tables.size()==1,"expected one " + spec.table + " table, got " + tables.size());
        Node table=tables.get(0), target=null;
        for (Entry e: table.children) if (e instanceof Node && ((Node)e).name.equals(spec.nodeName())) {
            require(target==null,"duplicate " + spec.nodeName()); target=(Node)e;
        }
        require(target!=null,"missing " + spec.nodeName());
        Prop hz=property(target,"opp-hz");
        require(hz!=null && hz.data.length==8 && readLong(hz.data,0)==((long)spec.mhz*1000000L),"opp-hz mismatch");
        return target;
    }
    private static void findTables(Node n,String name,List<Node> out) {
        if (n.name.equals(name)) out.add(n);
        for (Entry e:n.children) if (e instanceof Node) findTables((Node)e,name,out);
    }
    private static OppState state(Node node, Opp spec) {
        Prop uv=property(node,"opp-microvolt-vf0403");
        require(uv!=null && uv.data.length==4,"expected single-cell vf0403 voltage");
        Prop turbo=property(node,"turbo-mode");
        require(turbo==null || turbo.data.length==0,"turbo-mode must be boolean");
        int microvolts=readInt(uv.data,0);
        require(microvolts==0 || microvolts==spec.microvolts,"unexpected VF0403 voltage " + microvolts + " for " + spec.mhz);
        return new OppState(spec.mhz,microvolts,turbo!=null);
    }
    public static OppState inspect(byte[] dtb,int mhz) {
        Opp spec=approved(mhz);
        return state(locate(parse(dtb),spec),spec);
    }
    public static byte[] patch(byte[] dtb,int mhz,boolean unlock) {
        Opp spec=approved(mhz);
        Tree tree=parse(dtb);
        Node target=locate(tree,spec);
        OppState before=state(target,spec);
        require(before.enabled || (before.microvolts==0 && !before.turbo),
                "ambiguous partially enabled OPP; refuse to patch");
        if (before.enabled==unlock) return Arrays.copyOf(dtb,dtb.length);
        property(target,"opp-microvolt-vf0403").data=u32(unlock?spec.microvolts:0);
        if (unlock) {
            target.children.add(new Prop("turbo-mode",new byte[0]));
        } else {
            for(int i=target.children.size()-1;i>=0;i--) {
                Entry e=target.children.get(i);
                if (e instanceof Prop && ((Prop)e).name.equals("turbo-mode")) target.children.remove(i);
            }
        }
        byte[] result=serialize(tree);
        OppState after=inspect(result,mhz);
        require(after.enabled==unlock && after.microvolts==(unlock?spec.microvolts:0),"output OPP verification");
        for (Opp other: APPROVED) if (other.mhz!=mhz) {
            OppState a=inspect(dtb,other.mhz), b=inspect(result,other.mhz);
            require(a.microvolts==b.microvolts && a.turbo==b.turbo,"changed unrelated OPP " + other.mhz);
        }
        return result;
    }
    /** Applies a nine-bit configuration in approvedFrequenciesMhz() order. */
    public static byte[] patchConfiguration(byte[] original,String mask) {
        require(mask!=null && mask.matches("[01]{9}"),"nine OPP settings required");
        byte[] updated=Arrays.copyOf(original,original.length);
        for(int i=0;i<APPROVED.length;i++) {
            int mhz=APPROVED[i].mhz;
            boolean enabled=mask.charAt(i)=='1';
            if(inspect(updated,mhz).enabled!=enabled) updated=patch(updated,mhz,enabled);
        }
        return updated;
    }
    private static int findNameOff(byte[] str,String name) {
        byte[] search=(name+"\0").getBytes(StandardCharsets.US_ASCII);
        for(int pos=0;pos<str.length;) {
            int end=pos;
            while(end<str.length && str[end]!=0)end++;
            if (end<str.length && end-pos+1==search.length) {
                boolean match=true;
                for (int i=0;i<search.length;i++) if (str[pos+i]!=search[i]) { match=false; break; }
                if (match) return pos;
            }
            pos=end+1;
        }
        return -1;
    }
    private static final class StringPool {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        StringPool(byte[] original) { out.write(original,0,original.length); }
        int offset(String name) {
            byte[] current=out.toByteArray();
            int found=findNameOff(current,name);
            if (found>=0) return found;
            int where=out.size();
            byte[] encoded=(name+"\0").getBytes(StandardCharsets.US_ASCII);
            out.write(encoded,0,encoded.length);
            return where;
        }
    }
    private static void emitNode(ByteArrayOutputStream structure,Node node,StringPool pool) {
        emitInt(structure,FDT_BEGIN_NODE);
        emitZ(structure,node.name);
        for(Entry e:node.children) {
            if (e instanceof Node) emitNode(structure,(Node)e,pool);
            else if(e instanceof Prop) {
                Prop p=(Prop)e;
                emitInt(structure,FDT_PROP);
                emitInt(structure,p.data.length);
                emitInt(structure,pool.offset(p.name));
                structure.write(p.data,0,p.data.length);
                emitPad(structure);
            } else emitInt(structure,FDT_NOP);
        }
        emitInt(structure,FDT_END_NODE);
    }
    private static byte[] serialize(Tree tree) {
        StringPool pool=new StringPool(tree.strings);
        ByteArrayOutputStream st=new ByteArrayOutputStream();
        emitNode(st,tree.root,pool);
        emitInt(st,FDT_END);
        byte[] stBytes=st.toByteArray();
        byte[] strings=pool.out.toByteArray();
        int stringOffset=(tree.structOffset+stBytes.length+3)&~3;
        int total=stringOffset+strings.length;
        require(total<=MAX_DTB_BYTES,"repacked DTB too big");
        byte[] result=new byte[total];
        System.arraycopy(tree.original,0,result,0,tree.structOffset);
        System.arraycopy(stBytes,0,result,tree.structOffset,stBytes.length);
        System.arraycopy(strings,0,result,stringOffset,strings.length);
        putInt(result,4,total);
        putInt(result,12,stringOffset);
        putInt(result,32,strings.length);
        putInt(result,36,stBytes.length);
        parse(result);
        return result;
    }
}
