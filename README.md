# Compiler Project — Group 11a

## Group Members

| Name | Student Number |
|------|---------------|
| Afonso Cruz | 202006020 |
| Afonso Saraiva | 2020304461 |
| Daniel Pinto | 202304957 |
| Tomás Barros | 202303664 |

## Participation

Afonso Cruz — 25%
Afonso Saraiva — 25%
Daniel Pinto — 25%
Tomás Barros — 25%

## Self-Evaluation

| | Grade (0–20) |
|-|-------------|
| **Overall project** | 18 |
| Afonso Cruz | 18 |
| Afonso Saraiva | 18 |
| Daniel Pinto | 18 |
| Tomás Barros | 18 |

## Extensions Developed

| Extension | Status | Description |
|-----------|--------|-------------|
| **Arbitrary field position** | Implemented |  class fields generated at the start of the Jasmin class regardless of declaration order |
| **Method visibility modifiers** | Implemented |  `public`, `private`, `protected` correctly emitted in Jasmin |
| **Field initializers** | Implemented |  field initialization code emitted inside the `<init>` constructor |
| **if without else** | Implemented |  correct Jasmin control flow generated |
| **for loop** | Implemented |  Jasmin code generated for `for` statements |
| **do-while loop** | Implemented |  Jasmin code generated for `do-while` statements |
| **Additional arithmetic operators** (`%`) | Implemented |  `rem` instruction emitted |
| **Additional logical operators** (`&&`, `\|\|`) | Implemented |  short-circuit evaluation via OLLIR branches |
| **Local static method calls** | Implemented |  `invokestatic` emitted for same-class static calls |
| **new with arguments** | Implemented |  arguments loaded before `invokespecial` |
| **Implicit this** | Implemented |  instance method calls on current object resolve correctly |
| **Array initializer** | Implemented |  Jasmin code generated for array literal initializers |
| **Multidimensional arrays** | Implemented |  `multianewarray`, `aaload`, `aastore` emitted correctly |

## Declaration of AI Tools Used

AI tools/services used in this work:

[x] The following tools were used:

 - **Claude (Anthropic)**: Throughout the project, we used Claude to help us better understand relevant concepts and technologies. It was particularly useful when we had questions about specific implementation details or needed clearer explanations of topics. We also used it to assist with documenting the code through explanatory comments and to review parts of our implementation, providing suggestions and alternative approaches for features that were more challenging to develop.

[x] All content has been reviewed, understood, validated, and we assume full responsibility for the work in this repository.


# Repository Structure

The base repository has several folders, the main ones are:

- `src`: The source folder for the project, you will work here.
- `test`: Folder for your own tests.
- `test-public`: Public tests, similar to the majority of the private tests that will be used for evaluation. **Do not change the contents of this folder.** This folder will be modified by automatic updates during the semester.


The remaining folders are:

- `libs`: Libraries in JAR format, required for the project.
- `libs-jmm`: Java code that can be imported in your Java-- classes. Contains a `java` folder, with the source code, and a `compiled` folder with the same classes, in compiled format. The build system automatically compiles the files inside the `java` folder and stores them in the `compiled` folder.

