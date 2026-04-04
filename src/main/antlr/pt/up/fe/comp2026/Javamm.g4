grammar Javamm;

@header {
    package pt.up.fe.comp2026;
}

// Keywords
IMPORT: 'import';
PACKAGE: 'package';
CLASS : 'class' ;
EXTENDS : 'extends' ;
PUBLIC: 'public';
PRIVATE: 'private';
PROTECTED: 'protected';
STATIC : 'static' ;
RETURN : 'return' ;
IF : 'if' ;
ELSE : 'else' ;
WHILE : 'while' ;
DO : 'do';
FOR : 'for';
THIS : 'this';
TRUE : 'true';
FALSE : 'false';

// Types
BOOLEAN: 'boolean';
INT : 'int' ;
STRING : 'String';
VOID : 'void' ;

// Literals & identifiers
INTEGERDEF : '0' | [1-9][0-9]* ;
STRINGDEF: '"' (~["\r\n])* '"';
ID : [a-zA-Z_$] [a-zA-Z0-9_$]* ;

// Symbols
DOT : '.' ;
COMMA : ',' ;
SEMICOLON : ';' ;
ASSIGN : '=' ;
LT : '<' ;
LSB : '[' ;
RSB : ']' ;
LBRACE : '{' ;
RBRACE : '}' ;
NOT: '!' ;
AND : '&&' ;
OR : '||' ;

// Whitespace & comments
SINGLE_COMMENT: '//' ~[\r\n]*-> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;
WS : [ \t\n\r\f]+ -> skip ;

// ── Grammar rules ──────────────────────────────────────────────

program
    : packageDecl+ importDecl* classNode=classDecl EOF
    ;

packageDecl
    : PACKAGE path+=ID (DOT path+=ID)* SEMICOLON
    ;

importDecl
    : IMPORT path+=ID (DOT path+=ID)* SEMICOLON
    ;

classDecl
    : CLASS name=ID (EXTENDS parent=ID)?
        LBRACE
        (varDecl | methodDecl)*
        RBRACE
    ;

visibilityDeclaration
    : visibility=(PUBLIC | PRIVATE | PROTECTED)
    ;

type
    : name=(INT | BOOLEAN | STRING | VOID | ID) arrayDim+ #ArrayType
    | name=(INT | BOOLEAN | STRING | VOID | ID) #SimpleType
    ;

arrayDim
    : LSB RSB
    ;

param
    : type name=ID
    ;

varDecl
    : type name=ID ASSIGN expr ';'
    | type name=ID ';'
    ;

methodDecl locals[boolean isStatic=false]
    : visibilityDeclaration? (STATIC {$isStatic=true;})?
        returnType=type name=ID
        '(' (param (COMMA param)*)? ')'
        LBRACE varDecl* stmt* RBRACE
    ;

stmt
    : LBRACE stmt* RBRACE #ComplexStmt
    | IF '(' expr ')' stmt (ELSE stmt)? #IfStmt
    | WHILE '(' expr ')' stmt #WhileStmt
    | DO stmt WHILE '(' expr ')' SEMICOLON #DoStmt
    | FOR '(' forInit? SEMICOLON forCond? SEMICOLON forUpdate? ')' stmt #ForStmt
    | returnStmt #FullReturnStmt
    | assignStmt SEMICOLON #FullAssignStmt
    | expr SEMICOLON #SimpleStmt
    ;

returnStmt
    : RETURN SEMICOLON
    | RETURN expr SEMICOLON
    ;

assignStmt
    : var=ID (LSB expr RSB)+ ASSIGN right=expr
    | var=ID ASSIGN right=expr
    ;

forInit
    : assignStmt
    ;

forCond
    : expr
    ;

forUpdate
    : exprList
    | assignStmt
    ;

exprList
    : expr (COMMA expr)*
    ;

expr
    : NOT expr #NotExpr
    | '++' expr #UnaryExpr
    | '--' expr #UnaryExpr
    | '+' expr #UnaryExpr
    | '-' expr #UnaryExpr
    | expr op=('*'|'/'|'%') expr #BinaryExpr
    | expr op=('+'|'-') expr #BinaryExpr
    | expr op=('<'|'>'|'<='|'>=') expr #BinaryExpr
    | expr op=('=='|'!=') expr #BinaryExpr
    | expr op=AND expr #BinaryExpr
    | expr op=OR expr #BinaryExpr
    | primary #PrimaryExpr
    ;

primary
    : baseExpr postfix* #PostfixExpr
    ;

baseExpr
    : THIS #ThisLiteral
    | TRUE #TrueLiteral
    | FALSE #FalseLiteral
    | INTEGERDEF #IntegerLiteral
    | 'new' name=ID '(' exprList? ')' #NewObjectExpr
    | 'new' type '(' ')' #NewObjectExpr
    | 'new' type '[' expr ']' #NewArrayExpr
    | 'new' type '[' ']' '{' exprList? '}' #NewArrayLiteralExpr
    | name=ID '(' exprList? ')' #CallExpr
    | name=ID #VarRefExpr
    | '(' expr ')' #ParenExpr
    ;

postfix
    : '[' expr ']' #ArrayExpr
    | '.' name=ID '(' exprList? ')' #ObjectCallExpr
    | '.' name=ID #FieldAccessExpr
    ;