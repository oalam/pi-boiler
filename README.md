
# install

sudo apt-get install wiringpi

# Comment compiler le programme

    sbt clean package
    sbt assemblyPackageDependency


# Comment lancer le programme

    cd apps/
    sudo nohup java -cp regulation_2.11-1.7.jar:regulation-assembly-1.3-deps.jar org.oalam.regulation.api.Boot &
    sudo tail -f nohup.out 


# Dépendances



    mvn clean install -P raspberrypi