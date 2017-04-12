# ArgInOut
In class, we went through a "HelloWorld" example. In Part II, we will first go through the process of deploying this example on the DE1SoC board. 

Before you start, make sure that you are in the directory where you have your spatial set up.
First, initialize the environment variables by running:
```bash
source init-env.sh
```
## Completing ArgInOut
Take a look at $SPATIAL_HOME/apps/src/ArgInOut.scala, and complete it by following the comments in the file. 
Before running apps, we need to first make them by running:
```bash
make apps
```

## Simulating Apps
### Functional Simulation
First, we want to make sure that the app is functionally correct. To do so, run: 
```bash
bin/spatial ArgInOut --scala
```
This will generate the app files under ./gen/ArgInOut. Navigate into ./gen/ArgInOut and run the functional simulation with argument set to 3:
```bash
cd ./gen/ArgInOut
chmod +x run.sh
./run.sh 3
```
You will see the following messages in your terminal: 
```bash
[info] Running Main 3
expected: 7
result: 7
```

### Cycle-accurate Simulation
After verifying the basic functions, we want to start generating circuit designs. To do so, you need to first go back to the home directory of spatial and then generate the chisel project files for ArgInOut:
```bash
cd $SPATIAL_HOME
bin/spatial ArgInOut --chisel
```

This will generate the hardware description files under ./gen/ArgInOut. Navigate into ./gen/ArgInOut, and you will see the following folders: 
```bash
chisel
cpp
```

The chisel folder contains descriptions for running designs on FPGA, and the cpp file contains C++ code that runs on the CPU side. To generate cycle-accurate simulation, we need to first scp the generated project folder onto a server that has VCS installed. In this class, we will use the tucson server to run our simulation. Run: 
```bash
cd $SPATIAL_HOME
scp -r gen/ArgInOut USER_NAME@tucson.stanford.edu:~/YOUR_TARGET_DIR
```
YOUR_TARGET_DIR is a placeholder. You should replace it with your working directory on the server.
On the server side, first navigate into the project folder, then run: 
```bash
make vcs
./Top YOUR_ARG_IN
```
This will initiate VCS simulation on tucson. You will observe something similar to the following outputs on your terminal:
```bash
tianzhao@tucson:~/synthTest/ArgInOut$ ./run.sh 3
[WARNING]: DELITE_NUM_THREADS undefined, defaulting to 1
Executing with 1 thread(s)
Chronologic VCS simulator copyright 1991-2015
Contains Synopsys proprietary information.
Compiler version K-2015.09-SP2-7_Full64; Runtime version K-2015.09-SP2-7_Full64;  Apr 11 23:37 2017
[SIM] Sim process started!
idealDRAM = 0
Connection successful!
== Loading device model file '/home/tianzhao/synthTest/ArgInOut/verilog/DRAMSim2/ini/DDR2_micron_16M_8b_x8_sg3E.ini' == 
== Loading system model file '/home/tianzhao/synthTest/ArgInOut/verilog/DRAMSim2/spatial.dram.ini' == 
===== MemorySystem 0 =====
CH. 0 TOTAL_STORAGE : 16384MB | 16 Ranks | 8 Devices per rank
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
writing vis file to /home/tianzhao/synthTest/ArgInOut/verilog/DRAMSim2/results/dramSimVCS/DDR2_micron_16M_8b_x8_sg3E/16GB.1Ch.16R.scheme2.open_page.32TQ.32CQ.RtB.pRank.vis
DRAMSim2 Clock Frequency =333333333Hz, CPU Clock Frequency=150000000Hz
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
Design ran for 8 cycles, status = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
Kernel done, test run time = 0 ms
[readOutputStream] data = 374a656e, tag = 8b3dfe16, last = 1
expected: 7
result: 7
Received SIGHUP (signal 1), exiting.
			V C S   S i m u l a t i o n   R e p o r t 
			Time: 59000 ps
			CPU Time:      0.500 seconds;       Data structure size:   0.0Mb
			Tue Apr 11 23:37:01 2017
			Realistic DRAM Simulation

```

## Synthesizing and Running Spatial Apps on DE1-SoC
On tucson, under the project folder, run:
```bash
make de1soc
```
The synthesis process will start, and will take roughly 15 min to finish. After the synthesis finishes, you will see two generated files under ./prog in the project directory:
```bash
Top
sp.rbf
program_de1soc.sh
```
Top is the binary that runs on the ARM core, sp.rbf is the bitstream that runs on the FPGA, and program_de1soc.sh is the shell script that programs the FPGA with bitstream. To test them, you will need to copy ./prog to DE1SoC.

To run the app, in the session that connects to your DE1SoC, you will need to enter: 
```bash
sudo ./Top YOUR_ARG_IN
```
Here is an example of running ArgInOut with YOUR_ARG_IN set to 4 on DE1SoC. Your result should look quite similar to this one:
```bash
tianzhao@client: sudo ./Top 4
[WARNING]: DELITE_NUM_THREADS undefined, defaulting to 1
Executing with 1 thread(s)
Running cmd ./program_de1soc.sh ./sp.rbf
Disabling fpga2hps bridge...
Disabling hps2fpga bridge...
Disabling lwhps2fpga bridge...
Loading ./sp.rbf into FPGA device...
2+1 records in
2+1 records out
2892552 bytes (2.9 MB) copied, 0.199989 s, 14.5 MB/s
Enabling fpga2hps bridge...
Enabling hps2fpga bridge...
Enabling lwhps2fpga bridge...
Running design..
Design done, ran for 0.001710 secs
Kernel done, test run time = 0 ms
expected: 8
result: 8
```

All the exercises will be under $SPATIAL_HOME/apps/problems. After implementing an app in ./problems, you will need to copy it over to $SPATIAL_HOME/apps/src. Every time the $SPATIAL_HOME/apps/src directory is updated, you will need to re-make the apps by running: 

# Generate Sum Using FIFO, Reduce and Foreach 
In this example, we would like to implement an accelerator that takes in a number x, adds from 1 to up to x (not including x), and then return the sum. To make the testing easier, we are setting the size of FIFO to 16. The input number x should be a multiple of 16. Please take a look at apps/src/FifoPushPop.scala and complete the design by following the comments.

# MemReduce
In this example, we are using MemReduce to produce the following matrix A:

A(row, col) = 32 * (row + col), where A is 32 by 32. 

If you observe that synthesizer takes longer than usual to finish, this is expected because we are synthesizing SRAMs. 

# Streaming Video
On DE1SoC, the video decoder sends in a 24-bit RGB value; however the VGA port only accepts 16-bit RGB data. In this streaming example, we are implementing an RGB space converter that converts 24-bit RGB to 16-bit RGB. Please take a look at apps/src/RGBConvert.scala, implement and simulate your design. To deploy it on board, you will need to change the bus names. The detailed instructions can be found in RGBConvert.scala. 


# Set up Working Environment on tucson
We will be using the tucson.stanford.edu server to run simulation and synthesis. 
Once you log in to tucson, add the following lines to your .bashrc: 
```bash
export LM_LICENSE_FILE=7195@cadlic0.stanford.edu
export VCS_HOME=/cad/synopsys/vcs/K-2015.09-SP2-7
export PATH=/usr/bin:$VCS_HOME/amd64/bin:$PATH
export LM_LICENSE_FILE=27000@cadlic0.stanford.edu:$LM_LICENSE_FILE
export PATH=$PATH:/opt/intelFPGA_lite/16.1/quartus/bin

```
This lines include Quartus and VCS onto your $PATH.
