#!bin/bash

# $1 = flags (--retime, --syncMem, etc)
# $2 = modifier ("retime", "basic", etc..)
# $3 = make target ("zynq", "aws-F1-afi")
cd /home/mattfel/aws-fpga/
source /home/mattfel/aws-fpga/hdk_setup.sh
source /home/mattfel/aws-fpga/sdk_setup.sh

cd ${SPATIAL_HOME}/apps
ab=`git rev-parse --abbrev-ref HEAD`
cd ../
if [[ $ab != "regression" ]]; then 
	read -p "You seem to be on an apps branch that is not regression.  Continue? [y/N]: " choice
	echo    # (optional) move to a new line
	case "$choice" in 
	  y|Y ) echo "Continuing..";;
	  n|N ) exit 1;;
	  * ) exit 1;;
	esac
fi

cd $SPATIAL_HOME

# annotated_list=(`cat ${SPATIAL_HOME}/apps/src/MachSuite.scala | grep "// Regression" | sed 's/object //g' | sed 's/ extends.*//g'`)
annotated_list=("Stencil3D" 
				"NW" 
				"Viterbi" 
				"EdgeDetector" 
				"MD_Grid" 
				"FFT_Strided" 
				"Backprop" 
				"Gibbs_Ising2D" 
				"GEMM_Blocked"
				# "SPMV_CRS" 
				# "BFS_Queue" 
				# "PageRank" 
				# "BlackScholes" 
				# "Sort_Merge" 
				# "KMP" 
				# "TPCHQ6" 
				# "AES" 
				# "SHA" 
				"Kmeans")

				# "LeNet" "DjinnASR" "VGG16"  
				# "KalmanFilter" "GACT" "AlexNet" "Network_in_Network" 
				# "VGG_CNN_S" "Overfeat" "Cifar10_Full"  )
for a in ${annotated_list[@]}; do
	if [[ $1 = "1" ]]; then
		rt="--retiming"
	else 
		rt=""
	fi
	cd $SPATIAL_HOME
	rm -rf out_$2_$a
	bin/spatial $a --synth --out=out_$2_$a $rt
	cd out_$2_$a
	make $3 &
	wait 10
done
