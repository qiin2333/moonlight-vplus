package com.limelight.binding.audio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class Ac3Iec61937PacketizerTest {
    private byte[] frame(int code, int bytes, int bsmod) {
        byte[] f = new byte[bytes];
        for (int i=6;i<bytes;i++) f[i]=(byte)(i*17);
        f[0]=0x0b; f[1]=0x77; f[4]=(byte)code; f[5]=(byte)(0x40|bsmod);
        return f;
    }
    @Test public void packsPreamblePayloadAndPadding() {
        byte[] f=frame(36,2560,3);
        List<short[]> out=new ArrayList<>();
        new Ac3Iec61937Packetizer().append(f,f.length,w->out.add(w.clone()));
        short[] w=out.get(0);
        assertEquals(3072,w.length);
        assertEquals(0xf872,w[0]&65535); assertEquals(0x4e1f,w[1]&65535);
        assertEquals(0x301,w[2]&65535); assertEquals(20480,w[3]&65535);
        for(int i=0;i<f.length;i+=2) assertEquals(((f[i]&255)<<8)|(f[i+1]&255),w[4+i/2]&65535);
        for(int i=4+f.length/2;i<w.length;i++) assertEquals(0,w[i]);
    }
    @Test public void acceptsEveryPossibleSplitAndCoalescedFrames() {
        byte[] f=frame(36,2560,0);
        for(int split=0;split<=f.length;split++) {
            Ac3Iec61937Packetizer p=new Ac3Iec61937Packetizer();
            List<short[]> out=new ArrayList<>();
            p.append(Arrays.copyOfRange(f,0,split),split,w->out.add(w.clone()));
            p.append(Arrays.copyOfRange(f,split,f.length),f.length-split,w->out.add(w.clone()));
            assertEquals(1,out.size());
        }
        byte[] two=new byte[f.length*2];System.arraycopy(f,0,two,0,f.length);System.arraycopy(f,0,two,f.length,f.length);
        List<short[]> out=new ArrayList<>();new Ac3Iec61937Packetizer().append(two,two.length,w->out.add(w.clone()));
        assertEquals(2,out.size());assertArrayEquals(out.get(0),out.get(1));
    }
    @Test public void supportsAll48kFrameSizesAndClearsReusedBuffer() {
        int[] rates={32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
        Ac3Iec61937Packetizer p=new Ac3Iec61937Packetizer();
        for(int code=37;code>=0;code--) {
            byte[] f=frame(code,rates[code/2]*4,0);List<short[]> out=new ArrayList<>();
            p.append(f,f.length,w->out.add(w.clone()));
            assertEquals(f.length*8,out.get(0)[3]&65535);
            for(int i=4+f.length/2;i<3072;i++) assertEquals(0,out.get(0)[i]);
        }
    }
    @Test public void rejectsInvalidHeadersAndCanReset() {
        for(int variant=0;variant<4;variant++) {
            byte[] f=frame(36,2560,0);
            if(variant==0)f[0]=0; if(variant==1)f[4]=38;
            if(variant==2)f[4]=(byte)(36|64); if(variant==3)f[5]=(byte)(16<<3);
            Ac3Iec61937Packetizer p=new Ac3Iec61937Packetizer();
            assertThrows(IllegalArgumentException.class,()->p.append(f,f.length,w->fail()));
            byte[] good=frame(36,2560,0);List<short[]> out=new ArrayList<>();
            p.append(good,good.length,w->out.add(w.clone()));assertEquals(1,out.size());
        }
        Ac3Iec61937Packetizer p=new Ac3Iec61937Packetizer();byte[] good=frame(36,2560,0);
        p.append(good,10,w->fail());p.reset();
        List<short[]> out=new ArrayList<>();p.append(good,good.length,w->out.add(w.clone()));assertEquals(1,out.size());
        assertThrows(IllegalArgumentException.class,()->p.append(good,-1,w->fail()));
        assertThrows(IllegalArgumentException.class,()->p.append(good,2561,w->fail()));
    }
    @Test public void writeFailureDiscardsPartialBurst() {
        Ac3Iec61937Packetizer p=new Ac3Iec61937Packetizer();byte[] f=frame(36,2560,0);
        assertThrows(IllegalStateException.class,()->p.append(f,f.length,w->{throw new IllegalStateException();}));
        List<short[]> out=new ArrayList<>();p.append(f,f.length,w->out.add(w.clone()));assertEquals(1,out.size());
    }
}
