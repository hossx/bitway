cd /var/bitway/code/bitway
git fetch
git checkout -b $1 origin/$1
git rebase origin/$1
branch=`git branch | grep "*" | awk '{print $2}'`
echo "current branch is "$branch
./activator clean
./activator assembly
cp bitway-backend/target/scala-2.10/bitway-backend-assembly-* /var/bitway/backend/
