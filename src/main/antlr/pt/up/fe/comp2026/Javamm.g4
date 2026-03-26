grammar Javamm;

@header {
    package pt.up.fe.comp2026;
}

CLASS : 'class' ;
PACKAGE: 'package';
IMPORT: 'import';
PUBLIC: 'public';
PRIVATE: 'private';
PROTECTED: 'protected';
EXTENDS : 'extends' ;
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

INT : 'int' ;
VOID : 'void' ;
STRING : 'String';
BOOLEAN : 'boolean';

STRINGDEF: '"' (~["\r\n])* '"';
INTEGERDEF : '0' | [1-9][0-9]* ;
ID : [a-zA-Z_$] [a-zA-Z0-9_$]* ;

WS : [ \t\n\r\f]+ -> skip ;
SEMICOLON : ';' ;
DOT : '.' ;
COMMA : ',' ;
ASSIGN : '=' ;
LT : '<' ;
LBRACE : '{' ;
RBRACE : '}' ;
LSB : '[' ;
RSB : ']' ;
NOT: '!' ;
AND : '&&' ;
OR : '||' ;

SINGLE_COMMENT: '//' ~[\r\n]* -> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;

program
    : packageDecl? importDecl* classNode=classDecl EOF
    ;

packageDecl
    : PACKAGE path+=ID (DOT path+=ID)* SEMICOLON
    ;

importDecl
    : IMPORT path+=ID (DOT path+=ID)* SEMICOLON
    ;

classDecl
    : CLASS name=ID (EXTENDS parent=ID)?
        '{'
        (varDecl | methodDecl)*
        '}'
    ;

visibilityDeclaration
    : visibility=(PUBLIC | PRIVATE | PROTECTED)
    ;

type
    : name=(INT | BOOLEAN | STRING | VOID | ID)            #SimpleType
    | name=(INT | BOOLEAN | STRING | VOID | ID) (LSB RSB)+ #ArrayType
    ;

param
    : type name=ID
    ;

varDecl
    : type name=ID ';'
    | type name=ID ASSIGN expr ';'
    ;

methodDecl locals[boolean isStatic=false]
    : visibilityDeclaration? (STATIC {$isStatic=true;})?
        returnType=type name=ID
        '(' (param (COMMA param)*)? ')'
        '{' varDecl* stmt* '}'
    ;

stmt
    : assignStmt ';'
    | returnStmt
    | IF '(' expr ')' stmt (ELSE stmt)?
    | WHILE '(' expr ')' stmt
    | DO stmt WHILE '(' expr ')' ';'
    | FOR '(' forInit? ';' forCond? ';' forUpdate? ')' stmt
    | '{' stmt* '}'
    | expr ';'
    ;

assignStmt
    : var=ID ASSIGN right=expr
    | var=ID LSB expr RSB ASSIGN right=expr
    ;

returnStmt
    : RETURN expr ';'
    | RETURN ';'
    ;

forInit
    : assignStmt
    ;

forCond
    : expr
    ;

forUpdate
    : assignStmt
    | exprList
    ;

exprList
    : expr (COMMA expr)*
    ;

expr
    : primary             #PrimaryExpr
    | expr AND expr       #AndExpr
    | expr OR expr        #OrExpr
    | NOT expr            #NotExpr
    | expr op='+' expr    #BinaryExpr
    | expr op='-' expr    #BinaryExpr
    | expr op='*' expr    #BinaryExpr
    | expr op='/' expr    #BinaryExpr
    | expr op='<' expr    #BinaryExpr
    | expr op='==' expr   #BinaryExpr
    ;
primary
    : baseExpr postfix*
    ;

baseExpr
    : INTEGERDEF                                    #IntegerLiteral
    | TRUE                                          #TrueLiteral
    | FALSE                                         #FalseLiteral
    | THIS                                          #ThisLiteral
    | 'new' ID '(' exprList? ')'                    #NewObjectExpr
    | 'new' type '(' ')'                            #NewObjectTypeExpr
    | 'new' type '[' ']' '{' exprList? '}'          #NewArrayLiteralExpr
    | 'new' type '[' expr ']'                       #NewArrayExpr
    | ID '(' exprList? ')'                          #CallExpr
    | ID                                            #VarRefExpr
    | '(' expr ')'                                  #ParenExpr
    ;

postfix
    : '.' name=ID '(' exprList? ')'  #ObjectCallExpr
    | '.' name=ID                    #FieldAccessExpr
    | '[' expr ']'                   #ArrayExpr
     ;