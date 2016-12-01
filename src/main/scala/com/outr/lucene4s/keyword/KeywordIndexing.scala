package com.outr.lucene4s.keyword

import com.outr.lucene4s._
import com.outr.lucene4s.document.DocumentBuilder
import com.outr.lucene4s.field.FieldType
import com.outr.scribe.Logging
import org.apache.lucene.document.{Document, Field}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.{IndexSearcher, MatchAllDocsQuery}
import org.apache.lucene.store.{FSDirectory, RAMDirectory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

case class KeywordIndexing(lucene: Lucene,
                           directoryName: String,
                           wordsFromBuilder: DocumentBuilder => List[String] = KeywordIndexing.DefaultWordsFromBuilder,
                           stopWords: Set[String] = KeywordIndexing.DefaultStopWords,
                           splitRegex: Option[String] = Some(KeywordIndexing.DefaultSplitRegex),
                           wordMatcherRegex: String = """[a-zA-Z0-9.]{2,}""") extends LuceneListener with Logging {
  // Write support
  private lazy val indexPath = lucene.directory.map(_.resolve(directoryName))
  private lazy val indexDirectory = indexPath.map(FSDirectory.open).getOrElse(new RAMDirectory)
  private lazy val indexWriterConfig = new IndexWriterConfig(lucene.standardAnalyzer)
  private lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)

  // Read support
  private var currentIndexReader: Option[DirectoryReader] = None

  // Automatically add the listener
  lucene.listen(this)

  private def indexReader: DirectoryReader = synchronized {
    val reader = currentIndexReader match {
      case Some(r) => Option(DirectoryReader.openIfChanged(r, indexWriter, true)) match {
        case Some(updated) if updated ne r => {         // New reader was assigned
          lucene.system.scheduler.scheduleOnce(30.seconds) {
            r.close()
          }
          updated
        }
        case _ => r                                     // null was returned
      }
      case None => DirectoryReader.open(indexWriter, true, true)
    }
    currentIndexReader = Some(reader)
    reader
  }
  private def searcher: IndexSearcher = new IndexSearcher(indexReader)

  override def indexed(builder: DocumentBuilder): Unit = {
    index(wordsFromBuilder(builder))
  }

  override def delete(): Unit = {
    indexWriter.deleteAll()
  }

  def index(words: List[String]): Unit = if (words.nonEmpty) {
    val unfilteredWords = splitRegex match {
      case Some(r) => words.flatMap(_.split(r))
      case None => words
    }
    val indexableWords = unfilteredWords.filterNot(stopWords.contains)
    indexableWords.foreach {
      case word if word.matches(wordMatcherRegex) => {
        val doc = new Document
        doc.add(new Field("keyword", word, FieldType.Stored.lucene()))
        val parser = new QueryParser("keyword", lucene.standardAnalyzer)
        val query = parser.parse(word)
        indexWriter.deleteDocuments(query)
        indexWriter.addDocument(doc)
        indexWriter.commit()
      }
      case _ => // Ignore empty words
    }
  }

  def search(queryString: String = "", limit: Int = 10): KeywordResults = {
    val searcher = this.searcher
    val query = queryString match {
      case "" => new MatchAllDocsQuery
      case _ => {
        val parser = new QueryParser("keyword", lucene.standardAnalyzer)
        parser.setAllowLeadingWildcard(true)
        parser.parse(queryString)
      }
    }
    val searchResults = searcher.search(query, limit)
    val keywords = searchResults.scoreDocs.map { scoreDoc =>
      val doc = searcher.doc(scoreDoc.doc)
      val word = doc.get("keyword")
      KeywordResult(word, scoreDoc.score.toDouble)
    }.toList
    KeywordResults(keywords, searchResults.totalHits, searchResults.getMaxScore)
  }
}

object KeywordIndexing {
  val DefaultStopWords: Set[String] = Set(
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "i", "if", "in", "into", "is",
    "no", "not", "of", "on", "or", "s", "such", "t", "that", "the", "their", "then", "there", "these",
    "they", "this", "to", "was", "will", "with"
  )
  val DefaultSplitRegex: String = """\s+"""
  val DefaultWordsFromBuilder: (DocumentBuilder) => List[String] = (builder: DocumentBuilder) => builder.fullText

  def FieldFromBuilder[T](field: com.outr.lucene4s.field.Field[T]): (DocumentBuilder) => List[String] = (builder: DocumentBuilder) => {
    List(builder.document.get(field.name))
  }
  def FieldWordsFromBuilder[T](field: com.outr.lucene4s.field.Field[T]): (DocumentBuilder) => List[String] = (builder: DocumentBuilder) => {
    builder.document.get(field.name).split(DefaultSplitRegex).toList
  }
}

case class KeywordResults(results: List[KeywordResult], total: Int, maxScore: Double)

case class KeywordResult(word: String, score: Double)