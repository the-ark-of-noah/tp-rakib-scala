# TP Scala Rakib

## Sujet
[Lien vers le sujet de Rakib](https://hackmd.io/@NoobZik/HkoGsdvWR#)

## Installation
### Requirements
- Java 8 (1.8)
- Scala 2.12
- SBT
- Intellij IDEA de préférence


### Pour tous
Vous devez télécharger le fichier csv sur [ce lien](https://moocs.scala-lang.org/~dockermoocs/bigdata/atussum.csv).
Ensuite, vous devez le placer dans le dossier `src/main/resources/timeusage`.

### Pour les Windows
Parce que Windows c'est toujours wow 🥰! Il faudra installer les winutils pour que hadoop accepte de fonctionner.
<p align="center">
	<img src="https://media1.tenor.com/m/i1rsgMyOFgcAAAAd/cat-cat-love.gif" alt="chat émerveillé">
</p>

[Le lien](https://github.com/robguilarr/spark-winutils-3.3.1) de téléchargement depuis Github (télécharger le zip idéalement). Le dossier qui nous intéresse est le `hadoop-3.3.1`. Une fois dézippé, on récupère ce dossier et on le colle dans notre projet, dans un dossier `winutils` à la racine (même niveau que le `build.sbt`.
