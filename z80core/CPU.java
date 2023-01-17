// Copyright (c) 2018 Douglas Miller <durgadas311@gmail.com>
package z80core;

public interface CPU {
	int getRegA();
	int getRegB();
	int getRegC();
	int getRegD();
	int getRegE();
	int getRegH();
	int getRegL();
	int getRegAF();
	int getRegBC();
	int getRegDE();
	int getRegHL();
	int getRegPC();
	int getRegSP();
	void setRegA(int a);
	void setRegB(int b);
	void setRegC(int c);
	void setRegD(int d);
	void setRegE(int e);
	void setRegH(int h);
	void setRegL(int l);
	void setRegAF(int psw);
	void setRegBC(int bc);
	void setRegDE(int de);
	void setRegHL(int hl);
	void setRegPC(int pc);
	void setRegSP(int sp);
	// TODO: support these?
	int getRegAFx();
	int getRegBCx();
	int getRegDEx();
	int getRegHLx();
	int getRegAx();
	int getRegFx();
	int getRegBx();
	int getRegCx();
	int getRegDx();
	int getRegEx();
	int getRegHx();
	int getRegLx();
	int getRegIX();
	int getRegIY();
	int getRegI();
	int getRegR();
	//
	void reset();
	boolean isIE();
	boolean isINTLine();
	void setINTLine(boolean intLine);
	boolean hasNMI();	// not for 8080/8085
	boolean isNMI();
	void triggerNMI();
	boolean hasINT1();	// Z180
	boolean isINT1Line();
	void setINT1Line(boolean intLine);
	boolean hasINT2();	// Z180
	boolean isINT2Line();
	void setINT2Line(boolean intLine);
	int execute();	// num clock cycles, - for interrupt, etc
	String specialCycle(); // for tracing, if execute() < 0
	void resetBreakpoints();
	String dumpDebug();
}
