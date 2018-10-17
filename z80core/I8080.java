// Copyright (c) 2018 Douglas Miller <durgadas311@gmail.com>

// Derived from Z80.java
// Douglas Miller <durgadas311@gmail.com>
//
package z80core;

import java.util.Arrays;
import z80core.Z80State.IntMode;

public class I8080 implements CPU {

	private final Computer computerImpl;
	private int ticks;
	private int opCode;
	private final boolean execDone;
	private static final int CARRY_MASK = 0x01;
	private static final int ADDSUB_MASK = 0x02;
	private static final int PARITY_MASK = 0x04;
	private static final int OVERFLOW_MASK = 0x04; // alias de PARITY_MASK
	private static final int BIT3_MASK = 0x08;
	private static final int HALFCARRY_MASK = 0x10;
	private static final int BIT5_MASK = 0x20;
	private static final int ZERO_MASK = 0x40;
	private static final int SIGN_MASK = 0x80;
	private static final int FLAG_53_MASK = BIT5_MASK | BIT3_MASK;
	private static final int FLAG_SZ_MASK = SIGN_MASK | ZERO_MASK;
	private static final int FLAG_SZHN_MASK = FLAG_SZ_MASK | HALFCARRY_MASK | ADDSUB_MASK;
	private static final int FLAG_SZP_MASK = FLAG_SZ_MASK | PARITY_MASK;
	private static final int FLAG_SZHP_MASK = FLAG_SZP_MASK | HALFCARRY_MASK;
	private int regA, regB, regC, regD, regE, regH, regL;
	private int sz5h3pnFlags;
	private boolean carryFlag;
	private boolean flagQ, lastFlagQ;
	private int regPC;
	private int regSP;
	private boolean ffIE = false;
	private boolean pendingEI = false;
	private boolean activeINT = false;
	private boolean intrFetch = false;
	private boolean halted = false;
	private boolean pinReset = false;
	private int memptr;
	private static final int sz53n_addTable[] = new int[256];
	private static final int sz53pn_addTable[] = new int[256];
	private static final int sz53n_subTable[] = new int[256];
	private static final int sz53pn_subTable[] = new int[256];

	static {
		boolean evenBits;

		for (int idx = 0; idx < 256; idx++) {
			if (idx > 0x7f) {
				sz53n_addTable[idx] |= SIGN_MASK;
			}

			evenBits = true;
			for (int mask = 0x01; mask < 0x100; mask <<= 1) {
				if ((idx & mask) != 0) {
					evenBits = !evenBits;
				}
			}

			sz53n_addTable[idx] |= (idx & FLAG_53_MASK);
			sz53n_subTable[idx] = sz53n_addTable[idx] | ADDSUB_MASK;

			if (evenBits) {
				sz53pn_addTable[idx] = sz53n_addTable[idx] | PARITY_MASK;
				sz53pn_subTable[idx] = sz53n_subTable[idx] | PARITY_MASK;
			} else {
				sz53pn_addTable[idx] = sz53n_addTable[idx];
				sz53pn_subTable[idx] = sz53n_subTable[idx];
			}
		}

		sz53n_addTable[0] |= ZERO_MASK;
		sz53pn_addTable[0] |= ZERO_MASK;
		sz53n_subTable[0] |= ZERO_MASK;
		sz53pn_subTable[0] |= ZERO_MASK;
	}

	private final boolean breakpointAt[] = new boolean[65536];

	public I8080(Computer impl) {
		computerImpl = impl;
		execDone = false;
		Arrays.fill(breakpointAt, false);
		reset();
	}

	public final int getRegA() {
		return regA;
	}

	public final void setRegA(int value) {
		regA = value & 0xff;
	}

	public final int getRegB() {
		return regB;
	}

	public final void setRegB(int value) {
		regB = value & 0xff;
	}

	public final int getRegC() {
		return regC;
	}

	public final void setRegC(int value) {
		regC = value & 0xff;
	}

	public final int getRegD() {
		return regD;
	}

	public final void setRegD(int value) {
		regD = value & 0xff;
	}

	public final int getRegE() {
		return regE;
	}

	public final void setRegE(int value) {
		regE = value & 0xff;
	}

	public final int getRegH() {
		return regH;
	}

	public final void setRegH(int value) {
		regH = value & 0xff;
	}

	public final int getRegL() {
		return regL;
	}

	public final void setRegL(int value) {
		regL = value & 0xff;
	}

	public final int getRegAx() { return 0; }

	public final void setRegAx(int value) { }

	public final int getRegFx() { return 0; }

	public final void setRegFx(int value) { }

	public final int getRegBx() { return 0; }

	public final void setRegBx(int value) { }

	public final int getRegCx() { return 0; }

	public final void setRegCx(int value) { }

	public final int getRegDx() { return 0; }

	public final void setRegDx(int value) { }

	public final int getRegEx() { return 0; }

	public final void setRegEx(int value) { }

	public final int getRegHx() { return 0; }

	public final void setRegHx(int value) { }

	public final int getRegLx() { return 0; }

	public final void setRegLx(int value) { }

	public final int getRegAF() {
		return (regA << 8) | (carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags);
	}

	public final void setRegAF(int word) {
		regA = (word >>> 8) & 0xff;

		sz5h3pnFlags = word & 0xfe;
		carryFlag = (word & CARRY_MASK) != 0;
	}

	public final int getRegAFx() { return 0; }

	public final void setRegAFx(int word) { }

	public final int getRegBC() {
		return (regB << 8) | regC;
	}

	public final void setRegBC(int word) {
		regB = (word >>> 8) & 0xff;
		regC = word & 0xff;
	}

	private void incRegBC() {
		if (++regC < 0x100) {
			return;
		}

		regC = 0;

		if (++regB < 0x100) {
			return;
		}

		regB = 0;
	}

	private void decRegBC() {
		if (--regC >= 0) {
			return;
		}

		regC = 0xff;

		if (--regB >= 0) {
			return;
		}

		regB = 0xff;
	}

	public final int getRegBCx() { return 0; }

	public final void setRegBCx(int word) { }

	public final int getRegDE() {
		return (regD << 8) | regE;
	}

	public final void setRegDE(int word) {
		regD = (word >>> 8) & 0xff;
		regE = word & 0xff;
	}

	private void incRegDE() {
		if (++regE < 0x100) {
			return;
		}

		regE = 0;

		if (++regD < 0x100) {
			return;
		}

		regD = 0;
	}

	private void decRegDE() {
		if (--regE >= 0) {
			return;
		}

		regE = 0xff;

		if (--regD >= 0) {
			return;
		}

		regD = 0xff;
	}

	public final int getRegDEx() { return 0; }

	public final void setRegDEx(int word) { }

	public final int getRegHL() {
		return (regH << 8) | regL;
	}

	public final void setRegHL(int word) {
		regH = (word >>> 8) & 0xff;
		regL = word & 0xff;
	}

	private void incRegHL() {
		if (++regL < 0x100) {
			return;
		}

		regL = 0;

		if (++regH < 0x100) {
			return;
		}

		regH = 0;
	}

	private void decRegHL() {
		if (--regL >= 0) {
			return;
		}

		regL = 0xff;

		if (--regH >= 0) {
			return;
		}

		regH = 0xff;
	}

	public final int getRegHLx() { return 0; }

	public final void setRegHLx(int word) { }

	public final int getRegPC() {
		return regPC;
	}

	public final void setRegPC(int address) {
		regPC = address & 0xffff;
	}

	public final int getRegSP() {
		return regSP;
	}

	public final void setRegSP(int word) {
		regSP = word & 0xffff;
	}

	public final int getRegIX() { return 0; }

	public final void setRegIX(int word) { }

	public final int getRegIY() { return 0; }

	public final void setRegIY(int word) { }

	public final int getRegI() { return 0; }

	public final void setRegI(int value) { }

	public final int getRegR() { return 0; }

	public final void setRegR(int value) { }

	public final int getPairIR() { return 0; }

	public final int getMemPtr() {
		return memptr & 0xffff;
	}

	public final void setMemPtr(int word) {
		memptr = word & 0xffff;
	}

	public final boolean isCarryFlag() {
		return carryFlag;
	}

	public final void setCarryFlag(boolean state) {
		carryFlag = state;
	}

	public final boolean isAddSubFlag() {
		return (sz5h3pnFlags & ADDSUB_MASK) != 0;
	}

	public final void setAddSubFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= ADDSUB_MASK;
		} else {
			sz5h3pnFlags &= ~ADDSUB_MASK;
		}
	}

	public final boolean isParOverFlag() {
		return (sz5h3pnFlags & PARITY_MASK) != 0;
	}

	public final void setParOverFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= PARITY_MASK;
		} else {
			sz5h3pnFlags &= ~PARITY_MASK;
		}
	}

	public final boolean isBit3Flag() {
		return (sz5h3pnFlags & BIT3_MASK) != 0;
	}

	public final void setBit3Fag(boolean state) {
		if (state) {
			sz5h3pnFlags |= BIT3_MASK;
		} else {
			sz5h3pnFlags &= ~BIT3_MASK;
		}
	}

	public final boolean isHalfCarryFlag() {
		return (sz5h3pnFlags & HALFCARRY_MASK) != 0;
	}

	public final void setHalfCarryFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		} else {
			sz5h3pnFlags &= ~HALFCARRY_MASK;
		}
	}

	public final boolean isBit5Flag() {
		return (sz5h3pnFlags & BIT5_MASK) != 0;
	}

	public final void setBit5Flag(boolean state) {
		if (state) {
			sz5h3pnFlags |= BIT5_MASK;
		} else {
			sz5h3pnFlags &= ~BIT5_MASK;
		}
	}

	public final boolean isZeroFlag() {
		return (sz5h3pnFlags & ZERO_MASK) != 0;
	}

	public final void setZeroFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= ZERO_MASK;
		} else {
			sz5h3pnFlags &= ~ZERO_MASK;
		}
	}

	public final boolean isSignFlag() {
		return (sz5h3pnFlags & SIGN_MASK) != 0;
	}

	public final void setSignFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= SIGN_MASK;
		} else {
			sz5h3pnFlags &= ~SIGN_MASK;
		}
	}

	// Acceso a los flags F
	public final int getFlags() {
		return carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags;
	}

	public final void setFlags(int regF) {
		sz5h3pnFlags = regF & 0xfe;

		carryFlag = (regF & CARRY_MASK) != 0;
	}

	public boolean isIE() { return ffIE; }

	public final void triggerNMI() { }

	public final boolean isINTLine() {
		return activeINT;
	}

	public final void setINTLine(boolean intLine) {
		activeINT = intLine;
	}

	public final boolean isHalted() {
		return halted;
	}

	public void setHalted(boolean state) {
		halted = state;
	}

	public void setPinReset() {
		pinReset = true;
	}

	public final boolean isPendingEI() {
		return pendingEI;
	}

	public final void setPendingEI(boolean state) {
		pendingEI = state;
	}

	// Reset
	// TODO: confirm details with official documentation
	public final void reset() {
		if (pinReset) {
			pinReset = false;
		} else {
			regA = 0xff;
			setFlags(0xff);
			regB = 0xff;
			regC = 0xff;
			regD = 0xff;
			regE = 0xff;
			regH = 0xff;
			regL = 0xff;

			regSP = 0xffff;

			memptr = 0xffff;
		}

		regPC = 0;
		ffIE = false;
		pendingEI = false;
		activeINT = false;
		halted = false;
		intrFetch = false;
		lastFlagQ = false;
	}

	/*
	 * Half-carry flag:
	 *
	 * FLAG = (A^B^RESULT)&0x10  for any operation
	 *
	 * Overflow flag:
	 *
	 * FLAG = ~(A^B)&(B^RESULT)&0x80 for addition [ADD/ADC]
	 * FLAG = (A^B)&(A^RESULT)&0x80  for subtraction [SUB/SBC]
	 *
	 * For INC/DEC, you can use following simplifications:
	 *
	 * INC:
	 * H_FLAG = (RESULT&0x0F)==0x00
	 * V_FLAG = RESULT==0x80
	 *
	 * DEC:
	 * H_FLAG = (RESULT&0x0F)==0x0F
	 * V_FLAG = RESULT==0x7F
	 */
	// Incrementa un valor de 8 bits modificando los flags oportunos
	private int inc8(int oper8) {
		oper8 = (oper8 + 1) & 0xff;

		sz5h3pnFlags = sz53n_addTable[oper8];

		if ((oper8 & 0x0f) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (oper8 == 0x80) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
		return oper8;
	}

	// Decrementa un valor de 8 bits modificando los flags oportunos
	private int dec8(int oper8) {
		oper8 = (oper8 - 1) & 0xff;

		sz5h3pnFlags = sz53n_subTable[oper8];

		if ((oper8 & 0x0f) == 0x0f) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (oper8 == 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
		return oper8;
	}

	// Suma de 8 bits afectando a los flags
	private void add(int oper8) {
		int res = regA + oper8;

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53n_addTable[res];

		/* El módulo 16 del resultado será menor que el módulo 16 del registro A
		 * si ha habido HalfCarry. Sucede lo mismo para todos los métodos suma
		 * SIN carry */
		if ((res & 0x0f) < (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Suma con acarreo de 8 bits
	private void adc(int oper8) {
		int res = regA + oper8;

		if (carryFlag) {
			res++;
		}

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53n_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Suma dos operandos de 16 bits sin carry afectando a los flags
	private int add16(int reg16, int oper16) {
		oper16 += reg16;

		carryFlag = oper16 > 0xffff;
		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | ((oper16 >>> 8) & FLAG_53_MASK);
		oper16 &= 0xffff;

		if ((oper16 & 0x0fff) < (reg16 & 0x0fff)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		memptr = reg16 + 1;
		flagQ = true;
		return oper16;
	}

	// Suma con acarreo de 16 bits
	private void adc16(int reg16) {
		int regHL = getRegHL();
		memptr = regHL + 1;

		int res = regHL + reg16;
		if (carryFlag) {
			res++;
		}

		carryFlag = res > 0xffff;
		res &= 0xffff;
		setRegHL(res);

		sz5h3pnFlags = sz53n_addTable[regH];
		if (res != 0) {
			sz5h3pnFlags &= ~ZERO_MASK;
		}

		if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regHL ^ ~reg16) & (regHL ^ res)) > 0x7fff) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
	}

	// Resta de 8 bits
	private void sub(int oper8) {
		int res = regA - oper8;

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53n_subTable[res];

		/* El módulo 16 del resultado será mayor que el módulo 16 del registro A
		 * si ha habido HalfCarry. Sucede lo mismo para todos los métodos resta
		 * SIN carry, incluido cp */
		if ((res & 0x0f) > (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Resta con acarreo de 8 bits
	private void sbc(int oper8) {
		int res = regA - oper8;

		if (carryFlag) {
			res--;
		}

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53n_subTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Resta con acarreo de 16 bits
	private void sbc16(int reg16) {
		int regHL = getRegHL();
		memptr = regHL + 1;

		int res = regHL - reg16;
		if (carryFlag) {
			res--;
		}

		carryFlag = res < 0;
		res &= 0xffff;
		setRegHL(res);

		sz5h3pnFlags = sz53n_subTable[regH];
		if (res != 0) {
			sz5h3pnFlags &= ~ZERO_MASK;
		}

		if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regHL ^ reg16) & (regHL ^ res)) > 0x7fff) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}
		flagQ = true;
	}

	// Operación AND lógica
	private void and(int oper8) {
		regA &= oper8;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA] | HALFCARRY_MASK;
		flagQ = true;
	}

	// Operación XOR lógica
	private void xor(int oper8) {
		regA = (regA ^ oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
		flagQ = true;
	}

	// Operación OR lógica
	private void or(int oper8) {
		regA = (regA | oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
		flagQ = true;
	}

	// Operación de comparación con el registro A
	// es como SUB, pero solo afecta a los flags
	// Los flags SIGN y ZERO se calculan a partir del resultado
	// Los flags 3 y 5 se copian desde el operando (sigh!)
	private void cp(int oper8) {
		int res = regA - (oper8 & 0xff);

		carryFlag = res < 0;
		res &= 0xff;

		sz5h3pnFlags = (sz53n_addTable[oper8] & FLAG_53_MASK)
			| // No necesito preservar H, pero está a 0 en la tabla de todas formas
			(sz53n_subTable[res] & FLAG_SZHN_MASK);

		if ((res & 0x0f) > (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
	}

	// DAA
	private void daa() {
		int suma = 0;
		boolean carry = carryFlag;

		if ((sz5h3pnFlags & HALFCARRY_MASK) != 0 || (regA & 0x0f) > 0x09) {
			suma = 6;
		}

		if (carry || (regA > 0x99)) {
			suma |= 0x60;
		}

		if (regA > 0x99) {
			carry = true;
		}

		if ((sz5h3pnFlags & ADDSUB_MASK) != 0) {
			sub(suma);
			sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_subTable[regA];
		} else {
			add(suma);
			sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_addTable[regA];
		}

		carryFlag = carry;
		// Los add/sub ya ponen el resto de los flags
		flagQ = true;
	}

	// POP
	private int pop() {
		int word = peek16(regSP);
		regSP = (regSP + 2) & 0xffff;
		return word;
	}

	// PUSH
	private void push(int word) {
		regSP = (regSP - 1) & 0xffff;
		poke8(regSP, word >>> 8);
		regSP = (regSP - 1) & 0xffff;
		poke8(regSP, word);
	}

	// fetch instruction byte, from either regPC (incr PC) or interrupt
	private int fetch8() {
		int val;
		if (intrFetch) {
			val = computerImpl.intrResp(IntMode.IM0);
		} else {
			val = computerImpl.peek8(regPC);
			regPC = (regPC + 1) & 0xffff;
		}
		ticks += 3;
		return val;
	}

	private int fetch16() {
		int val = fetch8();
		val = (fetch8() << 8) | val;
		return val;
	}

	// fetch instruction byte for M1 cycle
	private int fetchOpcode() {
		ticks += 1;
		return fetch8();
	}

	private int peek8(int address) {
		int val = computerImpl.peek8(address);
		ticks += 3;
		return val;
	}

	private int peek16(int address) {
		// Z80 is little-endian
		int val = computerImpl.peek8(address);
		val = (computerImpl.peek8(address + 1) << 8) | val;
		ticks += 6;
		return val;
	}

	private void poke8(int address, int value) {
		computerImpl.poke8(address, value);
		ticks += 3;
	}

	private void poke16(int address, int value) {
		// Z80 is little-endian
		computerImpl.poke8(address, value & 0xff);
		computerImpl.poke8(address + 1, (value >> 8) & 0xff);
		ticks += 6;
	}

	//Interrupción
	/* Desglose de la interrupción, según el modo:
	 * IM0:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo y salto a N
	 * IM1:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto PC y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo PC y PC=0x0038
	 * IM2:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo
	 *      M4: 3 T-Estados -> leer byte bajo del vector de INT
	 *      M5: 3 T-Estados -> leer byte alto y saltar a la rutina de INT
	 */
	private void interruption() {

		//System.out.println(String.format("INT at %d T-States", tEstados));
//		int tmp = tEstados; // peek8 modifica los tEstados
		// Si estaba en un HALT esperando una INT, lo saca de la espera
		if (halted) {
			halted = false;
			regPC = (regPC + 1) & 0xffff;
		}

		ffIE = false;
		// total: 2+N t-states
		intrFetch = true;
		ticks += 2; // 2 WAIT states added by INTR ACK initial M1
		memptr = regPC;
		//System.out.println(String.format("Coste INT: %d", tEstados-tmp));
	}

	public final boolean isBreakpoint(int address) {
		return breakpointAt[address & 0xffff];
	}

	public final void setBreakpoint(int address, boolean state) {
		breakpointAt[address & 0xffff] = state;
	}

	public void resetBreakpoints() {
		Arrays.fill(breakpointAt, false);
	}

	public final int execute() {
		ticks = 0;

		// Ahora se comprueba si al final de la instrucción anterior se
		// encontró una interrupción enmascarable y, de ser así, se procesa.
		if (activeINT) {
			if (ffIE && !pendingEI) {
				lastFlagQ = false;
				interruption();
			}
		}

		if (breakpointAt[regPC]) {
			computerImpl.breakpoint();
		}

		opCode = fetchOpcode();

		flagQ = false;

		decodeOpcode(opCode);

		lastFlagQ = flagQ;

		// Si está pendiente la activación de la interrupciones y el
		// código que se acaba de ejecutar no es el propio EI
		if (pendingEI && opCode != 0xFB) {
			pendingEI = false;
		}

		if (execDone) {
			computerImpl.execDone();
		}
		intrFetch = false;
		return ticks;
	}

	private void decodeOpcode(int opCode) {

		switch (opCode) {
//			case 0x00:       /* NOP */
//				break;
			case 0x01: {     /* LD BC,nn */
				setRegBC(fetch16());
				break;
			}
			case 0x02: {     /* LD (BC),A */
				poke8(getRegBC(), regA);
				memptr = (regA << 8) | ((regC + 1) & 0xff);
				break;
			}
			case 0x03: {     /* INC BC */
				ticks += 2;
				incRegBC();
				break;
			}
			case 0x04: {     /* INC B */
				regB = inc8(regB);
				break;
			}
			case 0x05: {     /* DEC B */
				regB = dec8(regB);
				break;
			}
			case 0x06: {     /* LD B,n */
				regB = fetch8();
				break;
			}
			case 0x07: {     /* RLCA */
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (carryFlag) {
					regA |= CARRY_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x09: {     /* ADD HL,BC */
				ticks += 7;
				setRegHL(add16(getRegHL(), getRegBC()));
				break;
			}
			case 0x0A: {     /* LD A,(BC) */
				memptr = getRegBC();
				regA = peek8(memptr++);
				break;
			}
			case 0x0B: {     /* DEC BC */
				ticks += 2;
				decRegBC();
				break;
			}
			case 0x0C: {     /* INC C */
				regC = inc8(regC);
				break;
			}
			case 0x0D: {     /* DEC C */
				regC = dec8(regC);
				break;
			}
			case 0x0E: {     /* LD C,n */
				regC = fetch8();
				break;
			}
			case 0x0F: {     /* RRCA */
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (carryFlag) {
					regA |= SIGN_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x11: {     /* LD DE,nn */
				setRegDE(fetch16());
				break;
			}
			case 0x12: {     /* LD (DE),A */
				poke8(getRegDE(), regA);
				memptr = (regA << 8) | ((regE + 1) & 0xff);
				break;
			}
			case 0x13: {     /* INC DE */
				ticks += 2;
				incRegDE();
				break;
			}
			case 0x14: {     /* INC D */
				regD = inc8(regD);
				break;
			}
			case 0x15: {     /* DEC D */
				regD = dec8(regD);
				break;
			}
			case 0x16: {     /* LD D,n */
				regD = fetch8();
				break;
			}
			case 0x17: {     /* RLA */
				boolean oldCarry = carryFlag;
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (oldCarry) {
					regA |= CARRY_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x19: {     /* ADD HL,DE */
				ticks += 7;
				setRegHL(add16(getRegHL(), getRegDE()));
				break;
			}
			case 0x1A: {     /* LD A,(DE) */
				memptr = getRegDE();
				regA = peek8(memptr++);
				break;
			}
			case 0x1B: {     /* DEC DE */
				ticks += 2;
				decRegDE();
				break;
			}
			case 0x1C: {     /* INC E */
				regE = inc8(regE);
				break;
			}
			case 0x1D: {     /* DEC E */
				regE = dec8(regE);
				break;
			}
			case 0x1E: {     /* LD E,n */
				regE = fetch8();
				break;
			}
			case 0x1F: {     /* RRA */
				boolean oldCarry = carryFlag;
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (oldCarry) {
					regA |= SIGN_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x21: {     /* LD HL,nn */
				setRegHL(fetch16());
				break;
			}
			case 0x22: {     /* LD (nn),HL */
				memptr = fetch16();
				poke16(memptr++, getRegHL());
				break;
			}
			case 0x23: {     /* INC HL */
				ticks += 2;
				incRegHL();
				break;
			}
			case 0x24: {     /* INC H */
				regH = inc8(regH);
				break;
			}
			case 0x25: {     /* DEC H */
				regH = dec8(regH);
				break;
			}
			case 0x26: {     /* LD H,n */
				regH = fetch8();
				break;
			}
			case 0x27: {     /* DAA */
				daa();
				break;
			}
			case 0x29: {     /* ADD HL,HL */
				ticks += 7;
				int work16 = getRegHL();
				setRegHL(add16(work16, work16));
				break;
			}
			case 0x2A: {     /* LD HL,(nn) */
				memptr = fetch16();
				setRegHL(peek16(memptr++));
				break;
			}
			case 0x2B: {     /* DEC HL */
				ticks += 2;
				decRegHL();
				break;
			}
			case 0x2C: {     /* INC L */
				regL = inc8(regL);
				break;
			}
			case 0x2D: {     /* DEC L */
				regL = dec8(regL);
				break;
			}
			case 0x2E: {     /* LD L,n */
				regL = fetch8();
				break;
			}
			case 0x2F: {     /* CPL */
				regA ^= 0xff;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | HALFCARRY_MASK
					| (regA & FLAG_53_MASK) | ADDSUB_MASK;
				flagQ = true;
				break;
			}
			case 0x31: {     /* LD SP,nn */
				regSP = fetch16();
				break;
			}
			case 0x32: {     /* LD (nn),A */
				memptr = fetch16();
				poke8(memptr, regA);
				memptr = (regA << 8) | ((memptr + 1) & 0xff);
				break;
			}
			case 0x33: {     /* INC SP */
				ticks += 2;
				regSP = (regSP + 1) & 0xffff;
				break;
			}
			case 0x34: {     /* INC (HL) */
				int work16 = getRegHL();
				int work8 = inc8(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x35: {     /* DEC (HL) */
				int work16 = getRegHL();
				int work8 = dec8(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x36: {     /* LD (HL),n */
				poke8(getRegHL(), fetch8());
				break;
			}
			case 0x37: {     /* SCF */
				int regQ = lastFlagQ ? sz5h3pnFlags : 0;
				carryFlag = true;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x39: {     /* ADD HL,SP */
				ticks += 7;
				setRegHL(add16(getRegHL(), regSP));
				break;
			}
			case 0x3A: {     /* LD A,(nn) */
				memptr = fetch16();
				regA = peek8(memptr++);
				break;
			}
			case 0x3B: {     /* DEC SP */
				ticks += 2;
				regSP = (regSP - 1) & 0xffff;
				break;
			}
			case 0x3C: {     /* INC A */
				regA = inc8(regA);
				break;
			}
			case 0x3D: {     /* DEC A */
				regA = dec8(regA);
				break;
			}
			case 0x3E: {     /* LD A,n */
				regA = fetch8();
				break;
			}
			case 0x3F: {     /* CCF */
				int regQ = lastFlagQ ? sz5h3pnFlags : 0;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
				if (carryFlag) {
					sz5h3pnFlags |= HALFCARRY_MASK;
				}
				carryFlag = !carryFlag;
				flagQ = true;
				break;
			}
//			case 0x40: {     /* LD B,B */
//				break;
//			}
			case 0x41: {     /* LD B,C */
				regB = regC;
				break;
			}
			case 0x42: {     /* LD B,D */
				regB = regD;
				break;
			}
			case 0x43: {     /* LD B,E */
				regB = regE;
				break;
			}
			case 0x44: {     /* LD B,H */
				regB = regH;
				break;
			}
			case 0x45: {     /* LD B,L */
				regB = regL;
				break;
			}
			case 0x46: {     /* LD B,(HL) */
				regB = peek8(getRegHL());
				break;
			}
			case 0x47: {     /* LD B,A */
				regB = regA;
				break;
			}
			case 0x48: {     /* LD C,B */
				regC = regB;
				break;
			}
//			case 0x49: {     /* LD C,C */
//				break;
//			}
			case 0x4A: {     /* LD C,D */
				regC = regD;
				break;
			}
			case 0x4B: {     /* LD C,E */
				regC = regE;
				break;
			}
			case 0x4C: {     /* LD C,H */
				regC = regH;
				break;
			}
			case 0x4D: {     /* LD C,L */
				regC = regL;
				break;
			}
			case 0x4E: {     /* LD C,(HL) */
				regC = peek8(getRegHL());
				break;
			}
			case 0x4F: {     /* LD C,A */
				regC = regA;
				break;
			}
			case 0x50: {     /* LD D,B */
				regD = regB;
				break;
			}
			case 0x51: {     /* LD D,C */
				regD = regC;
				break;
			}
//			case 0x52: {     /* LD D,D */
//				break;
//			}
			case 0x53: {     /* LD D,E */
				regD = regE;
				break;
			}
			case 0x54: {     /* LD D,H */
				regD = regH;
				break;
			}
			case 0x55: {     /* LD D,L */
				regD = regL;
				break;
			}
			case 0x56: {     /* LD D,(HL) */
				regD = peek8(getRegHL());
				break;
			}
			case 0x57: {     /* LD D,A */
				regD = regA;
				break;
			}
			case 0x58: {     /* LD E,B */
				regE = regB;
				break;
			}
			case 0x59: {     /* LD E,C */
				regE = regC;
				break;
			}
			case 0x5A: {     /* LD E,D */
				regE = regD;
				break;
			}
//			case 0x5B: {     /* LD E,E */
//				break;
//			}
			case 0x5C: {     /* LD E,H */
				regE = regH;
				break;
			}
			case 0x5D: {     /* LD E,L */
				regE = regL;
				break;
			}
			case 0x5E: {     /* LD E,(HL) */
				regE = peek8(getRegHL());
				break;
			}
			case 0x5F: {     /* LD E,A */
				regE = regA;
				break;
			}
			case 0x60: {     /* LD H,B */
				regH = regB;
				break;
			}
			case 0x61: {     /* LD H,C */
				regH = regC;
				break;
			}
			case 0x62: {     /* LD H,D */
				regH = regD;
				break;
			}
			case 0x63: {     /* LD H,E */
				regH = regE;
				break;
			}
//			case 0x64: {     /* LD H,H */
//				break;
//			}
			case 0x65: {     /* LD H,L */
				regH = regL;
				break;
			}
			case 0x66: {     /* LD H,(HL) */
				regH = peek8(getRegHL());
				break;
			}
			case 0x67: {     /* LD H,A */
				regH = regA;
				break;
			}
			case 0x68: {     /* LD L,B */
				regL = regB;
				break;
			}
			case 0x69: {     /* LD L,C */
				regL = regC;
				break;
			}
			case 0x6A: {     /* LD L,D */
				regL = regD;
				break;
			}
			case 0x6B: {     /* LD L,E */
				regL = regE;
				break;
			}
			case 0x6C: {     /* LD L,H */
				regL = regH;
				break;
			}
//			case 0x6D: {     /* LD L,L */
//				break;
//			}
			case 0x6E: {     /* LD L,(HL) */
				regL = peek8(getRegHL());
				break;
			}
			case 0x6F: {     /* LD L,A */
				regL = regA;
				break;
			}
			case 0x70: {     /* LD (HL),B */
				poke8(getRegHL(), regB);
				break;
			}
			case 0x71: {     /* LD (HL),C */
				poke8(getRegHL(), regC);
				break;
			}
			case 0x72: {     /* LD (HL),D */
				poke8(getRegHL(), regD);
				break;
			}
			case 0x73: {     /* LD (HL),E */
				poke8(getRegHL(), regE);
				break;
			}
			case 0x74: {     /* LD (HL),H */
				poke8(getRegHL(), regH);
				break;
			}
			case 0x75: {     /* LD (HL),L */
				poke8(getRegHL(), regL);
				break;
			}
			case 0x76: {     /* HALT */
				regPC = (regPC - 1) & 0xffff;
				halted = true;
				break;
			}
			case 0x77: {     /* LD (HL),A */
				poke8(getRegHL(), regA);
				break;
			}
			case 0x78: {     /* LD A,B */
				regA = regB;
				break;
			}
			case 0x79: {     /* LD A,C */
				regA = regC;
				break;
			}
			case 0x7A: {     /* LD A,D */
				regA = regD;
				break;
			}
			case 0x7B: {     /* LD A,E */
				regA = regE;
				break;
			}
			case 0x7C: {     /* LD A,H */
				regA = regH;
				break;
			}
			case 0x7D: {     /* LD A,L */
				regA = regL;
				break;
			}
			case 0x7E: {     /* LD A,(HL) */
				regA = peek8(getRegHL());
				break;
			}
//			case 0x7F: {     /* LD A,A */
//				break;
//			}
			case 0x80: {     /* ADD A,B */
				add(regB);
				break;
			}
			case 0x81: {     /* ADD A,C */
				add(regC);
				break;
			}
			case 0x82: {     /* ADD A,D */
				add(regD);
				break;
			}
			case 0x83: {     /* ADD A,E */
				add(regE);
				break;
			}
			case 0x84: {     /* ADD A,H */
				add(regH);
				break;
			}
			case 0x85: {     /* ADD A,L */
				add(regL);
				break;
			}
			case 0x86: {     /* ADD A,(HL) */
				add(peek8(getRegHL()));
				break;
			}
			case 0x87: {     /* ADD A,A */
				add(regA);
				break;
			}
			case 0x88: {     /* ADC A,B */
				adc(regB);
				break;
			}
			case 0x89: {     /* ADC A,C */
				adc(regC);
				break;
			}
			case 0x8A: {     /* ADC A,D */
				adc(regD);
				break;
			}
			case 0x8B: {     /* ADC A,E */
				adc(regE);
				break;
			}
			case 0x8C: {     /* ADC A,H */
				adc(regH);
				break;
			}
			case 0x8D: {     /* ADC A,L */
				adc(regL);
				break;
			}
			case 0x8E: {     /* ADC A,(HL) */
				adc(peek8(getRegHL()));
				break;
			}
			case 0x8F: {     /* ADC A,A */
				adc(regA);
				break;
			}
			case 0x90: {     /* SUB B */
				sub(regB);
				break;
			}
			case 0x91: {     /* SUB C */
				sub(regC);
				break;
			}
			case 0x92: {     /* SUB D */
				sub(regD);
				break;
			}
			case 0x93: {     /* SUB E */
				sub(regE);
				break;
			}
			case 0x94: {     /* SUB H */
				sub(regH);
				break;
			}
			case 0x95: {     /* SUB L */
				sub(regL);
				break;
			}
			case 0x96: {     /* SUB (HL) */
				sub(peek8(getRegHL()));
				break;
			}
			case 0x97: {     /* SUB A */
				sub(regA);
				break;
			}
			case 0x98: {     /* SBC A,B */
				sbc(regB);
				break;
			}
			case 0x99: {     /* SBC A,C */
				sbc(regC);
				break;
			}
			case 0x9A: {     /* SBC A,D */
				sbc(regD);
				break;
			}
			case 0x9B: {     /* SBC A,E */
				sbc(regE);
				break;
			}
			case 0x9C: {     /* SBC A,H */
				sbc(regH);
				break;
			}
			case 0x9D: {     /* SBC A,L */
				sbc(regL);
				break;
			}
			case 0x9E: {     /* SBC A,(HL) */
				sbc(peek8(getRegHL()));
				break;
			}
			case 0x9F: {     /* SBC A,A */
				sbc(regA);
				break;
			}
			case 0xA0: {     /* AND B */
				and(regB);
				break;
			}
			case 0xA1: {     /* AND C */
				and(regC);
				break;
			}
			case 0xA2: {     /* AND D */
				and(regD);
				break;
			}
			case 0xA3: {     /* AND E */
				and(regE);
				break;
			}
			case 0xA4: {     /* AND H */
				and(regH);
				break;
			}
			case 0xA5: {     /* AND L */
				and(regL);
				break;
			}
			case 0xA6: {     /* AND (HL) */
				and(peek8(getRegHL()));
				break;
			}
			case 0xA7: {     /* AND A */
				and(regA);
				break;
			}
			case 0xA8: {     /* XOR B */
				xor(regB);
				break;
			}
			case 0xA9: {     /* XOR C */
				xor(regC);
				break;
			}
			case 0xAA: {     /* XOR D */
				xor(regD);
				break;
			}
			case 0xAB: {     /* XOR E */
				xor(regE);
				break;
			}
			case 0xAC: {     /* XOR H */
				xor(regH);
				break;
			}
			case 0xAD: {     /* XOR L */
				xor(regL);
				break;
			}
			case 0xAE: {     /* XOR (HL) */
				xor(peek8(getRegHL()));
				break;
			}
			case 0xAF: {     /* XOR A */
				xor(regA);
				break;
			}
			case 0xB0: {     /* OR B */
				or(regB);
				break;
			}
			case 0xB1: {     /* OR C */
				or(regC);
				break;
			}
			case 0xB2: {     /* OR D */
				or(regD);
				break;
			}
			case 0xB3: {     /* OR E */
				or(regE);
				break;
			}
			case 0xB4: {     /* OR H */
				or(regH);
				break;
			}
			case 0xB5: {     /* OR L */
				or(regL);
				break;
			}
			case 0xB6: {     /* OR (HL) */
				or(peek8(getRegHL()));
				break;
			}
			case 0xB7: {     /* OR A */
				or(regA);
				break;
			}
			case 0xB8: {     /* CP B */
				cp(regB);
				break;
			}
			case 0xB9: {     /* CP C */
				cp(regC);
				break;
			}
			case 0xBA: {     /* CP D */
				cp(regD);
				break;
			}
			case 0xBB: {     /* CP E */
				cp(regE);
				break;
			}
			case 0xBC: {     /* CP H */
				cp(regH);
				break;
			}
			case 0xBD: {     /* CP L */
				cp(regL);
				break;
			}
			case 0xBE: {     /* CP (HL) */
				cp(peek8(getRegHL()));
				break;
			}
			case 0xBF: {     /* CP A */
				cp(regA);
				break;
			}
			case 0xC0: {     /* RET NZ */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xC1: {     /* POP BC */
				setRegBC(pop());
				break;
			}
			case 0xC2: {     /* JP NZ,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xC3: {     /* JP nn */
				memptr = regPC = fetch16();
				break;
			}
			case 0xC4: {     /* CALL NZ,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xC5: {     /* PUSH BC */
				++ticks;
				push(getRegBC());
				break;
			}
			case 0xC6: {     /* ADD A,n */
				add(fetch8());
				break;
			}
			case 0xC7: {     /* RST 00H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x00;
				break;
			}
			case 0xC8: {     /* RET Z */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xC9: {     /* RET */
				regPC = memptr = pop();
				break;
			}
			case 0xCA: {     /* JP Z,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xCC: {     /* CALL Z,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xCD: {     /* CALL nn */
				memptr = fetch16();
				++ticks;
				push(regPC);
				regPC = memptr;
				break;
			}
			case 0xCE: {     /* ADC A,n */
				adc(fetch8());
				break;
			}
			case 0xCF: {     /* RST 08H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x08;
				break;
			}
			case 0xD0: {     /* RET NC */
				++ticks;
				if (!carryFlag) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xD1: {     /* POP DE */
				setRegDE(pop());
				break;
			}
			case 0xD2: {     /* JP NC,nn */
				memptr = fetch16();
				if (!carryFlag) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xD3: {     /* OUT (n),A */
				int work8 = fetch8();
				memptr = regA << 8;
				computerImpl.outPort(memptr | work8, regA);
				ticks += 4;
				memptr |= ((work8 + 1) & 0xff);
				break;
			}
			case 0xD4: {     /* CALL NC,nn */
				memptr = fetch16();
				if (!carryFlag) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xD5: {     /* PUSH DE */
				++ticks;
				push(getRegDE());
				break;
			}
			case 0xD6: {     /* SUB n */
				sub(fetch8());
				break;
			}
			case 0xD7: {     /* RST 10H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x10;
				break;
			}
			case 0xD8: {     /* RET C */
				++ticks;
				if (carryFlag) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xDA: {     /* JP C,nn */
				memptr = fetch16();
				if (carryFlag) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xDB: {     /* IN A,(n) */
				memptr = (regA << 8) | fetch8();
				regA = computerImpl.inPort(memptr++);
				ticks += 4;
				break;
			}
			case 0xDC: {     /* CALL C,nn */
				memptr = fetch16();
				if (carryFlag) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xDE: {     /* SBC A,n */
				sbc(fetch8());
				break;
			}
			case 0xDF: {     /* RST 18H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x18;
				break;
			}
			case 0xE0:       /* RET PO */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					regPC = memptr = pop();
				}
				break;
			case 0xE1:       /* POP HL */
				setRegHL(pop());
				break;
			case 0xE2:       /* JP PO,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					regPC = memptr;
					break;
				}
				break;
			case 0xE3: {     /* EX (SP),HL */
				// Instrucción de ejecución sutil.
				int work16 = regH;
				int work8 = regL;
				setRegHL(peek16(regSP));
				++ticks;
				// No se usa poke16 porque el Z80 escribe los bytes AL REVES
				poke8((regSP + 1) & 0xffff, work16);
				poke8(regSP, work8);
				ticks += 2;
				memptr = getRegHL();
				break;
			}
			case 0xE4:       /* CALL PO,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xE5:       /* PUSH HL */
				++ticks;
				push(getRegHL());
				break;
			case 0xE6:       /* AND n */
				and(fetch8());
				break;
			case 0xE7:       /* RST 20H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x20;
				break;
			case 0xE8:       /* RET PE */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					regPC = memptr = pop();
				}
				break;
			case 0xE9:       /* JP (HL) */
				regPC = getRegHL();
				break;
			case 0xEA:       /* JP PE,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					regPC = memptr;
					break;
				}
				break;
			case 0xEB: {     /* EX DE,HL */
				int work8 = regH;
				regH = regD;
				regD = work8;

				work8 = regL;
				regL = regE;
				regE = work8;
				break;
			}
			case 0xEC:       /* CALL PE,nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xEE:       /* XOR n */
				xor(fetch8());
				break;
			case 0xEF:       /* RST 28H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x28;
				break;
			case 0xF0:       /* RET P */
				++ticks;
				if (sz5h3pnFlags < SIGN_MASK) {
					regPC = memptr = pop();
				}
				break;
			case 0xF1:       /* POP AF */
				setRegAF(pop());
				break;
			case 0xF2:       /* JP P,nn */
				memptr = fetch16();
				if (sz5h3pnFlags < SIGN_MASK) {
					regPC = memptr;
					break;
				}
				break;
			case 0xF3:       /* DI */
				ffIE = false;
				break;
			case 0xF4:       /* CALL P,nn */
				memptr = fetch16();
				if (sz5h3pnFlags < SIGN_MASK) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xF5:       /* PUSH AF */
				++ticks;
				push(getRegAF());
				break;
			case 0xF6:       /* OR n */
				or(fetch8());
				break;
			case 0xF7:       /* RST 30H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x30;
				break;
			case 0xF8:       /* RET M */
				++ticks;
				if (sz5h3pnFlags > 0x7f) {
					regPC = memptr = pop();
				}
				break;
			case 0xF9:       /* LD SP,HL */
				ticks += 2;
				regSP = getRegHL();
				break;
			case 0xFA:       /* JP M,nn */
				memptr = fetch16();
				if (sz5h3pnFlags > 0x7f) {
					regPC = memptr;
					break;
				}
				break;
			case 0xFB:       /* EI */
				ffIE = true;
				pendingEI = true;
				break;
			case 0xFC:       /* CALL M,nn */
				memptr = fetch16();
				if (sz5h3pnFlags > 0x7f) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xFE:       /* CP n */
				cp(fetch8());
				break;
			case 0xFF:       /* RST 38H */
				++ticks;
				push(regPC);
				regPC = memptr = 0x38;
		// default: treat like NOP...
		} /* del switch( codigo ) */
	}

	public String dumpDebug() {
		String s = new String();
		s += String.format("INT=%s IE=%s\n",
				isINTLine(), isIE());
		s += String.format("PC=%04x SP=%04x\n",
				getRegPC(), getRegSP());
		s += String.format("HL=%04x DE=%04x BC=%04x\n", getRegHL(), getRegDE(), getRegBC());
		s += String.format("A=%02x F=%s%s%s%s%s%s%s%s\n", regA,
			(sz5h3pnFlags & SIGN_MASK) == 0 ? "s" : "S",
			(sz5h3pnFlags & ZERO_MASK) == 0 ? "z" : "Z",
			(sz5h3pnFlags & BIT5_MASK) == 0 ? "." : "5",
			(sz5h3pnFlags & HALFCARRY_MASK) == 0 ? "h" : "H",
			(sz5h3pnFlags & BIT3_MASK) == 0 ? "." : "3",
			(sz5h3pnFlags & PARITY_MASK) == 0 ? "p" : "P",
			(sz5h3pnFlags & ADDSUB_MASK) == 0 ? "n" : "N",
			carryFlag ? "c" : "C"
			);
		return s;
	}
}
