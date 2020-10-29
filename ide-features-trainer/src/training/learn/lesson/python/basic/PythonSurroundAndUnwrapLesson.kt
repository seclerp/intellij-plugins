// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.learn.lesson.python.basic

import training.learn.LessonsBundle
import training.learn.interfaces.Module
import training.learn.lesson.general.SurroundAndUnwrapLesson
import training.learn.lesson.kimpl.LessonSample
import training.learn.lesson.kimpl.parseLessonSample

class PythonSurroundAndUnwrapLesson(module: Module) : SurroundAndUnwrapLesson(module, "Python") {
  override val sample: LessonSample = parseLessonSample("""
    def surround_and_unwrap_demo(debug):
        <select>if debug:
            print("Surround and Unwrap me!")</select>
    
  """.trimIndent())

  override val surroundItems = arrayOf("try", "except")

  override val lineShiftBeforeUnwrap = -2

  override val helpLinks: Map<String, String> = mapOf(
    Pair(LessonsBundle.message("surround.and.unwrap.help.surround.code.fragments"), "https://www.jetbrains.com/help/pycharm/surrounding-blocks-of-code-with-language-constructs.html"),
    Pair(LessonsBundle.message("surround.and.unwrap.help.unwrapping.and.removing.statements"), "https://www.jetbrains.com/help/pycharm/unwrapping-and-removing-statements.html"),
  )
}
