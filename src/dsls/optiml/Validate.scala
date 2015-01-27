package ppl.dsl.forge
package dsls
package optiml

import core.{ForgeApplication,ForgeApplicationRunner}

trait ValidateOps {
  this: OptiMLDSL =>

  def importValidateOps() {
    val DenseVector = lookupTpe("DenseVector")
    val DenseVectorView = lookupTpe("DenseVectorView")
    val DenseMatrix = lookupTpe("DenseMatrix")
    val TrainingSet = lookupTpe("TrainingSet")
    val Tup2 = lookupTpe("Tup2")
    val T = tpePar("T")
    val L = tpePar("L")
    val M = tpePar("M")
    val R = tpePar("R")

    val Validate = grp("Validate")

    direct (Validate) ("holdOut", (T,L), (("dataSet", TrainingSet(T,L)), ("pct", MDouble)) :: Tup2(TrainingSet(T,L),TrainingSet(T,L))) implements composite ${
      val (trainSet, testSet) = unpack((0::dataSet.numSamples) partition { e => random[Double] > pct })
      val (trainIndices, testIndices) = (IndexVector(trainSet), IndexVector(testSet))

      pack((TrainingSet(dataSet.data.apply(trainIndices), dataSet.labels.apply(trainIndices)),
            TrainingSet(dataSet.data.apply(testIndices), dataSet.labels.apply(testIndices))))
    }

    direct (Validate) ("confusionMatrix", T, MethodSignature(List(
                                            ("testSet",TrainingSet(T,MBoolean)),
                                            ("classify", DenseVectorView(T) ==> MBoolean),
                                            ("numSamples", MInt, "unit(-1)")
                                          ), DenseMatrix(MInt))) implements composite ${

      val numSamplesToProcess = if (numSamples == -1) testSet.numSamples else numSamples

      // returns [TP, FP; FN, TN]
      val stats = sum(0, numSamplesToProcess) { i =>
        // if (i > 0 && i % 10000 == 0) println("sample: " + i)

        val trueLabel = testSet.labels.apply(i)
        val predictedLabel = classify(testSet(i))

        if (trueLabel && predictedLabel) {
          DenseVector(1, 0, 0, 0)
        }
        else if (!trueLabel && predictedLabel) {
          DenseVector(0, 1, 0, 0)
        }
        else if (trueLabel && !predictedLabel) {
          DenseVector(0, 0, 1, 0)
        }
        else {
          DenseVector(0, 0, 0, 1)
        }

      }

      DenseMatrix(DenseVector(stats(0), stats(1)), DenseVector(stats(2), stats(3)))
    }

    direct (Validate) ("crossValidateRaw", (T,M,R), CurriedMethodSignature(List(List(
                                                 ("dataSet", TrainingSet(T,MBoolean)),
                                                 ("train", TrainingSet(T,MBoolean) ==> M),
                                                 ("_numFolds", MInt, "unit(10)")),
                                               List(
                                                 ("evalTestSet", (M,TrainingSet(T,MBoolean)) ==> R)
                                               )
                                            ), DenseVector(R))) implements composite ${


      fassert(dataSet.numSamples > 2, "Cannot cross validate dataset with less than 2 samples")

      val numFolds = max(min(_numFolds, dataSet.numSamples), 2)
      val shuffledIndices = shuffle(0::dataSet.numSamples)
      val numSamplesPerFold = dataSet.numSamples / numFolds

      (0::numFolds) { i =>
        val testSamplesOffset = i*numSamplesPerFold
        val testSampleIndices = testSamplesOffset::testSamplesOffset+numSamplesPerFold
        val trainingSampleLeftIndices = 0::max(0,testSamplesOffset-numSamplesPerFold).toInt
        val trainingSampleRightIndices = (testSamplesOffset+numSamplesPerFold)::dataSet.numSamples
        val trainingSampleIndices = IndexVector(trainingSampleLeftIndices << trainingSampleRightIndices)

        val sourceTrainingSampleIndices = shuffledIndices(trainingSampleIndices)
        val sourceTestSampleIndices = shuffledIndices(testSampleIndices)

        val trainingSet = TrainingSet(dataSet.data.apply(sourceTrainingSampleIndices), dataSet.labels.apply(sourceTrainingSampleIndices))
        val model = train(trainingSet)

        val testSet = TrainingSet(dataSet.data.apply(sourceTestSampleIndices), dataSet.labels.apply(sourceTestSampleIndices))
        evalTestSet(model, testSet)
      }
    }

    /*
     * Compute a cross-validated score for the classifier using a user-specified metric from a confusion matrix
     * to a score (e.g. accuracy, precision).
     */
    direct (Validate) ("crossValidate", (T,M), MethodSignature(List(
                                               ("dataSet", TrainingSet(T,MBoolean)),
                                               ("train", TrainingSet(T,MBoolean) ==> M),
                                               ("classify", (M,DenseVectorView(T)) ==> MBoolean),
                                               ("metric", DenseMatrix(MInt) ==> MDouble),
                                               ("numFolds", MInt, "unit(10)"),
                                               ("verbose", MBoolean, "unit(false)")
                                            ), MDouble)) implements composite ${

      def classifyWithModel(m: Rep[M])(x: Rep[DenseVectorView[T]]): Rep[Boolean] = classify(m, x)

      val foldResults = crossValidateRaw[T,M,Double](dataSet, train, numFolds) { (model, testSet) =>
        val conf = confusionMatrix(testSet, classifyWithModel(model))

        if (verbose) {
          println("confusionMatrix: [TP FP; FN TN]")
          conf.pprint
        }

        metric(conf)
      }

      mean(foldResults)
    }

    /*
     * Compute the cross-validated area under the ROC curve for a probabilistic classifier returning a value between 0.0 and 1.0,
     * evaluated with different classification thresholds. For each fold, we evaluate the AUC, and take the average across folds.
     */
    direct (Validate) ("crossValidateAUC", (T,M), MethodSignature(List(
                                               ("dataSet", TrainingSet(T,MBoolean)),
                                               ("train", TrainingSet(T,MBoolean) ==> M),
                                               ("classify", (M,DenseVectorView(T)) ==> MDouble),
                                               ("numFolds", MInt, "unit(10)"),
                                               ("numThresholds", MInt, "unit(10)")
                                            ), MDouble)) implements composite ${

      def classifyWithModel(m: Rep[M], t: Rep[Double])(x: Rep[DenseVectorView[T]]): Rep[Boolean] = classify(m, x) > t

      val AUCs = crossValidateRaw[T,M,Double](dataSet, train, numFolds) { (model, testSet) =>
        val ROC_curve = (0::numThresholds) { t =>
          val threshold = t.toDouble / numThresholds
          val conf = confusionMatrix(testSet, classifyWithModel(model, threshold))
          ROC(conf)
        }

        AUC(ROC_curve)
      }

      mean(AUCs)
    }

    direct (Validate) ("AUC", Nil, ("unsortedROCs", DenseVector(Tup2(MDouble,MDouble))) :: MDouble) implements composite ${
      val sorted = unsortedROCs.sortBy(t => t._1) // increasing by FPR (i.e. to the right)

      // add end points
      val curve = DenseVector(pack((unit(0.0),unit(0.0)))) << sorted << DenseVector(pack((unit(1.0),unit(1.0))))

      // trapezoidal approximation of area under the curve
      sum(0, curve.length-1) { i =>
        val x_i = curve(i)._1
        val x_i_next = curve(i+1)._1
        val y_i = curve(i)._2
        val y_i_next = curve(i+1)._2

        val base = x_i_next - x_i
        val height = (y_i_next + y_i) / 2.0
        base * height
      }
    }

    direct (Validate) ("accuracy", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      val TP = $0(0,0)
      val TN = $0(1,1)
      (TP + TN).toDouble / sum($0).toDouble
    }

    // Proportion of examples classified as positive that were actually positive.
    direct (Validate) ("precision", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      val TP = $0(0,0)
      val FP = $0(0,1)
      TP.toDouble / (TP + FP).toDouble
    }

    // a.k.a. True negative rate (TNR). Proportion of actual negative examples that were correctly classified.
    direct (Validate) ("specificity", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      val TN = $0(1,1)
      val FP = $0(0,1)
      TN.toDouble / (FP + TN).toDouble
    }

    // a.k.a. True positive rate (TPR). Proportion of actual positive examples that were correctly classified. a.k.a. Recall.
    direct (Validate) ("sensitivity", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      val TP = $0(0,0)
      val FN = $0(1,0)
      TP.toDouble / (TP + FN).toDouble
    }

    direct (Validate) ("recall", Nil, DenseMatrix(MInt) :: MDouble) implements redirect ${ sensitivity($0) }

    // a.k.a. False positive rate (FPR). Proportion of negative examples that were incorrectly classified.
    direct (Validate) ("fallout", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      1.0 - specificity($0)
    }

    direct (Validate) ("fscore", Nil, DenseMatrix(MInt) :: MDouble) implements composite ${
      2.0 / ((1.0 / precision($0)) + (1.0 / recall($0)))
    }

    // The (x,y) point on the ROC curve for the given classifier, representing (fpr, tpr)
    direct (Validate) ("ROC", Nil, DenseMatrix(MInt) :: Tup2(MDouble,MDouble)) implements composite ${
      pack((fallout($0), sensitivity($0)))
    }
  }
}