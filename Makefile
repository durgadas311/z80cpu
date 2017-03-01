all: z80core.jar

z80core.jar: z80core/*.java
	javac -source 1.7 -target 1.7 z80core/*.java
	jar -cf z80core.jar z80core/*.class

clean:
	rm -f z80core/*.class

src:
	cd ..; tar -czf z80cpu/z80cpu.tgz z80cpu/Makefile z80cpu/z80core/*.java
