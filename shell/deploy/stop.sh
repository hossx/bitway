ps -ef | grep "com.coinport.bitway.Main" | grep -v "grep" | awk '{print $2}' | xargs kill
