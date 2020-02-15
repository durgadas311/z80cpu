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
	void setRegPC(int pc);
	int getRegSP();
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
	void triggerNMI();
	int execute();	// num clock cycles, - for interrupt, etc
	String specialCycle(); // for tracing, if execute() < 0
	void resetBreakpoints();
	String dumpDebug();
}
