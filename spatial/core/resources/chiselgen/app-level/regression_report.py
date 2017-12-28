# This is called by scrape.sh

import gspread
import pygsheets
import sys
import os
from oauth2client.service_account import ServiceAccountCredentials
import datetime

#1 = branch
#2 = tid
#3 = appname
#3 = pass
#4 = cycles

tid = sys.argv[2]


# # gspread auth
# json_key = '/home/mattfel/regression/synth/key.json'
# scope = [
#     'https://spreadsheets.google.com/feeds',
#     'https://www.googleapis.com/auth/drive'
# ]
# credentials = ServiceAccountCredentials.from_json_keyfile_name(json_key, scope)

# pygsheets auth
json_key = '/home/mattfel/regression/synth/pygsheets_key.json'
gc = gspread.authorize(outh_file = json_key)

# sh = gc.open(sys.argv[1] + " Performance")
if (sys.argv[1] == "fpga"):
	sh = gc.open_by_key("1CMeHtxCU4D2u12m5UzGyKfB3WGlZy_Ycw_hBEi59XH8")
elif (sys.argv[1] == "develop"):
	sh = gc.open_by_key("13GW9IDtg0EFLYEERnAVMq4cGM7EKg2NXF4VsQrUp0iw")
elif (sys.argv[1] == "retime"):
	sh = gc.open_by_key("1glAFF586AuSqDxemwGD208yajf9WBqQUTrwctgsW--A")
elif (sys.argv[1] == "syncMem"):
	sh = gc.open_by_key("1TTzOAntqxLJFqmhLfvodlepXSwE4tgte1nd93NDpNC8")
elif (sys.argv[1] == "pre-master"):
	sh = gc.open_by_key("18lj4_mBza_908JU0K2II8d6jPhV57KktGaI27h_R1-s")
elif (sys.argv[1] == "master"):
	sh = gc.open_by_key("1eAVNnz2170dgAiSywvYeeip6c4Yw6MrPTXxYkJYbHWo")
else:
	print("No spreadsheet for " + sys.argv[4])
	exit()

# Get column
worksheet = sh.worksheet_by_title('Timestamps') # Select worksheet by index
lol = worksheet.get_all_values()
if (sys.argv[3] in lol[0]):
	col=lol[0].index(sys.argv[3])+1
	print("Col is %d" % col)
else:
	col=len(lol[0])+1
	print("Col is %d" % col)
	worksheet = sh.worksheet('Timestamps')
	worksheet.update_cell((1,col),sys.argv[3])
	worksheet = sh.worksheet('Runtime')
	worksheet.update_cell((1,2*col-7),sys.argv[3])


# Page 0 - Timestamps
stamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')

worksheet = sh.worksheet('Timestamps') # Select worksheet by index
worksheet.update_cell((tid,col), stamp)

# Page 1 - Runtime
worksheet = sh.worksheet('Runtime') # Select worksheet by index
worksheet.update_cell((tid,2*col-7),sys.argv[5])
worksheet.update_cell((tid,2*col-6),sys.argv[4])

# Page 2 - STATUS
worksheet = sh.worksheet('STATUS')
worksheet.update_cell((22,3),stamp)
worksheet.update_cell((22,4),os.uname()[1])