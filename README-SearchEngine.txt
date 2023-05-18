README

Instructions for a Windows Operation System

1) SOLR Installation

1- Install Solr, go to this webside "https://solr.apache.org/downloads.html"  to obtain assistance of how download Solr in your computer.
2- Go to this link "https://solr.apache.org/guide/7_0/installing-solr.html" to obtain assistance of how to intall Solr in your computer.
3- Once Solr has been install and well configured in your device, run the solr server using the following command in your terminal "solr start" If the system does not reconize the command, please go to step number 2 to obtain assitance of how to configure Solr in your computer.


2) CETUS SOURCE TO SOURCE COMPILER INFRASTRUCTURE

1-Download the Cetus source to source compiler infracstructure, go to this github
repository and download the file https://github.com/mgltorsa/The-Cetus-Project/tree/data-minning.
2- Click on <> Code, and the click on copy
3- Then go to the terminal where you want to download the file and type "git clone https://github.com/mgltorsa/The-Cetus-Project.git"
4- Type "git checkout data-minning" which is the branch where our implementation is located.
5- Once cetus is downloaded in your location, open the folder using visual studio code.
6- Run the following command "./build.sh bin", which will generate the binary file
7- Run the follwing command "./cetus -DataMining-analysis program.c" and the name of the C program or the programs where we want
to use the search engine. Once this step is completed, all the elements in the program will be already inside of Solr.




3) Running the Search Engine System

1) Once Solr is running succesfully, go to http://localhost:8983/solr/#/
2) Go to "Core Admin" which is in the left hand side of the screen
3) Go to "Add core" which is in the top of the screen, and add the following information
	3.1) Name: smd
	3.2) instanceDir: "C:\*\SearchDataMining\The-Cetus-Project\solr\sdm" in this case is where you have 
		located the project.
	3.3) dataDir: "C:\*\SearchDataMining\The-Cetus-Project\solr\sdm\data"
4) Click on "Add core"
5) Once the code has been added, go to "Core Selector" which is on the left hand side of the screen.
6) If the Core was succesfully added to the system, it will shown in the list of cores, if it does not, please go to step 3 and repeat
   the process or look for assitance in the Solr documentation.
7) Click on "sdm"
8) Click on "Query", which is in the left hand side of the screen.
9) on the "q" field type "Content:" followed by the query you want to pass to the system. For example: "Content: I want loops in my program". Start playing with different queries.
10) On the defType option select "lucene" which is the system that contains the Okapi BM25 algorithm"
11) Click on "Execute Query"
12) See the results in the right hand side of the screen
13) Enjoy our Search Engine Implementation.