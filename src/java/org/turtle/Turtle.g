grammar Turtle;

options {
    language = Java;
    output = AST;
    // ASTLabelType = TurtleAst;
    // TokenLabelType = TurtleToken;
}
// use these only in case of dire need
// backtrack = true;
// memoize = true;

// specify package that generated files will reside in
@header {
    package org.turtle;
}

// lexer

@lexer::header {
    package org.turtle;
}

@lexer::members {

}

// tokens

fragment ULEFTARROW  : '\u2190' ;
fragment UUPPERARROW : '\u2191' ;

// tokens {
//     Indent;
//     Dedent;
// }

fragment NL  : '\r'? '\n' | '\r';
/* FIXME */
Indent       : '_indent' ;
Dedent       : '_dedent' ;


KW_TO        : 'to' ;
KW_RETURN    : 'return' ;
KW_IF        : 'if' ;
KW_THEN      : 'then' ;
KW_ELSE      : 'else' ;
KW_REPEAT    : 'repeat' ;
KW_UNTIL     : 'until' ;
KW_FOREVER   : 'forever' ;
KW_FOR       : 'for' ;
KW_IN        : 'in' ;
// KW_EXECUTE   : 'execute' ;

KW_ASSIGN    : '<-'
             | ULEFTARROW
             ;

COMMA        : ',' ;

LESS_THAN    : '<' ;
GREATER_THAN : '>' ;
EQUAL        : '=' ;

PLUS         : '+' ;
MINUS        : '-' ;
MULT         : '*' ;
DIV          : '/' ;
LPAREN       : '(' ;
RPAREN       : ')' ;
LBRACE       : '[' ;
RBRACE       : ']' ;

EXP          : '**'
             | UUPPERARROW
             ;

NAME         : ('a'..'z' | 'A'..'Z' | '_' | '.')+ ;

INTEGER      : ('0'..'9')+ ;
REAL         : ('0'..'9')* '.' ('0'..'9')+ ;
STRING       : '"' (options { greedy = false; } : (~('"' | '\\') | '\\"' | '\\\\')*) '"' ;

WS           : (' ' | '\t' | NL )+ { $channel = HIDDEN; } ;
COMMENT      : ';' (~'\n') * { $channel = HIDDEN; } ;

// predicates like <, >, = return "true" or "false"

// if identifier resolves to procedure without arguments then call every
// time that identifier is mentioned

// built-in functions:
// forward, back, left, right
// print
// sqrt
// both, either - take 2 arguments, work like "logical and" and "logical or"
// remainder
// setheading, heading
// item(list, n) - return nth item starting from 1
// first(list) == item(list, 1)
// rest - return list without first item
// execute - execute a string of commands

// grammar

// <var>! - discard var
// <var>^ - make <var> parent of the current node
//
// deal with dangling "else" like this
// stat : "if" expr "then" stat
//        ( options {greedy=true;} : "else" stat)?
//      | ID
//      ;

// empty programs could be accepted but why would we want to do that?
program           : (proc_def | statement)+ EOF;

proc_def          : KW_TO NAME proc_def_arglist block ;
proc_def_arglist  : (options { greedy = false; } : binding_place*)
                  | LPAREN! (binding_place (options { greedy = false; } : (COMMA! binding_place)*))? RPAREN!
                  ;
binding_place : NAME
              | LBRACE (options { greedy = false; } : binding_place*) RBRACE
              ;

block : Indent! (options { greedy = false; } : statement+) Dedent! ;

// statements must support indentation
statement : conditional
          | repeat
          | assignment
          | return_statement
          | proc_call // usual expr wouldn't do here because it has no effects anyway
          ;


conditional       : KW_IF expr
                    (KW_THEN block | Indent KW_THEN block Dedent)
                    (options { greedy = true; } :
                     (KW_ELSE block | Indent KW_ELSE block Dedent))?
                  ;

repeat            : KW_REPEAT KW_UNTIL expr block
                  | KW_REPEAT block KW_UNTIL expr // loop with postcondition
                  | KW_REPEAT expr block // expr must evaluate to number
                  | KW_REPEAT KW_FOREVER block
                  | KW_REPEAT KW_FOR repeat_range_expr block
                  ;

repeat_range_expr : NAME EQUAL expr KW_TO expr // both exprs must evaluate to number
                  | binding_place KW_IN list
                  ;

assignment        : binding_place KW_ASSIGN! expr ;

return_statement : KW_RETURN expr ;



// procedures always return one value no matter what (e.g. [], the empty list,
// if no returns were specified)
proc_call         : NAME proc_call_arglist ;
proc_call_arglist : //(options { greedy = true; } : expr*) |
                  LPAREN! (expr (COMMA! expr)*)? RPAREN! ;

expr : cmp_expr ;

cmp_expr : add_expr ((LESS_THAN | GREATER_THAN | EQUAL) add_expr)? ;

add_expr : mult_expr (options { greedy = true; } : ((PLUS | MINUS) mult_expr)*) ;
mult_expr : exp_expr (options { greedy = true; } : ((MULT | DIV) exp_expr)*) ;
exp_expr  : atom_expr (EXP atom_expr)? ;

atom_expr : MINUS? (value
                   | proc_call
                   | NAME
                   | LPAREN expr^ RPAREN)
          ;


value : number
      | STRING
      | list
      ;

number : INTEGER
       | REAL
       ;

list : LBRACE! (expr (COMMA! expr)*)? RBRACE!;


