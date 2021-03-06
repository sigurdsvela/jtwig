/**
 * Copyright 2012 Lyncode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lyncode.jtwig.parser;

import com.lyncode.jtwig.exception.*;
import com.lyncode.jtwig.resource.JtwigResource;
import com.lyncode.jtwig.tree.api.Argumentable;
import com.lyncode.jtwig.tree.content.*;
import com.lyncode.jtwig.tree.documents.JtwigDocument;
import com.lyncode.jtwig.tree.documents.JtwigExtendsDocument;
import com.lyncode.jtwig.tree.documents.JtwigRootDocument;
import com.lyncode.jtwig.tree.helper.ElementList;
import com.lyncode.jtwig.tree.structural.BlockExpression;
import com.lyncode.jtwig.tree.structural.ExtendsExpression;
import com.lyncode.jtwig.tree.structural.IncludeExpression;
import com.lyncode.jtwig.tree.value.*;
import org.parboiled.BaseParser;
import org.parboiled.Rule;
import org.parboiled.annotations.DontLabel;
import org.parboiled.annotations.MemoMismatches;
import org.parboiled.annotations.SuppressNode;
import org.parboiled.annotations.SuppressSubnodes;
import org.parboiled.common.FileUtils;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.ParsingResult;

import java.nio.charset.Charset;

import static com.lyncode.jtwig.parser.JtwigKeyword.*;
import static com.lyncode.jtwig.parser.JtwigSymbol.*;
import static com.lyncode.jtwig.tree.content.IfExpression.ElseExpression;
import static com.lyncode.jtwig.tree.content.IfExpression.ElseIfExpression;
import static com.lyncode.jtwig.util.Simplifier.simplify;
import static org.parboiled.Parboiled.createParser;

public class JtwigParser extends BaseParser<Object> {
    public static JtwigDocument parse (JtwigResource input) throws ParseException {
        try {
            ReportingParseRunner<Object> runner = new ReportingParseRunner<Object>(createParser(JtwigParser.class).Start());
            ParsingResult<Object> result = runner.run(FileUtils.readAllText(input.retrieve(), Charset.defaultCharset()));
            return (JtwigDocument) result.resultValue;
        } catch (ParserRuntimeException e) {
            if (e.getCause() instanceof ParseBypassException) {
                ParseException innerException = ((ParseBypassException) e.getCause()).getInnerException();
                innerException.setExpression(e.getMessage());
                throw innerException;
            } else
                throw new ParseException(e);
        } catch (ResourceException e) {
            throw new ParseException(e);
        }
    }

    protected boolean Debug() {
        System.out.println("Level: " + this.getContext().getLevel());

        if (!this.getContext().getValueStack().isEmpty()) {
            int i = 0;
            for (Object v : this.getContext().getValueStack())
                System.out.println("Position " + (i++) + " of the stack " + v.getClass().getSimpleName() + " = '" + v.toString() + "'");
        }

        System.out.println("Left to process: " + this.getContext().getMatch());
        return true;
    }

    protected Rule Start() {
        return FirstOf(
                ExtendingTemplate(),
                NormalTemplate()
        );
    }

    protected Rule Ensure(ParseException e, Rule... innerRule) {
        return FirstOf(
                Sequence(innerRule),
                throwException(e)
        );
    }

    protected boolean throwException(ParseException exception) throws ParseBypassException {
        throw new ParseBypassException(exception);
    }

    protected Rule Content() {
        return Sequence(
                push(new Content()),
                ZeroOrMore(
                        FirstOf(
                                AddToContent(FastExpression()),
                                AddToContent(BlockExpression()),
                                AddToContent(IncludeExpression()),
                                AddToContent(ForExpression()),
                                AddToContent(IfExpression()),
                                AddToContent(SetExpression()),
                                AddToContent(Verbatim()),
                                Sequence(
                                        OpenCode(),
                                        TestNot(
                                                FirstOf(
                                                        SpecificKeyword(ENDBLOCK),
                                                        SpecificKeyword(ENDFOR),
                                                        SpecificKeyword(ENDIF),
                                                        SpecificKeyword(IF),
                                                        SpecificKeyword(BLOCK),
                                                        SpecificKeyword(FOR),
                                                        SpecificKeyword(SET),
                                                        SpecificKeyword(ELSE),
                                                        SpecificKeyword(ELSEIF)
                                                )
                                        ),
                                        throwException(new UnknownExpressionException())
                                ),
                                AddToContent(TextExpression())
                        )
                )
        );
    }

    protected Rule Verbatim () {
        return Sequence(
                Sequence(
                        OpenCode(),
                        SpecificKeyword(VERBATIM),
                        CloseCode()
                ),
                TextExpression(Sequence(OpenCode(), SpecificKeyword(JtwigKeyword.ENDVERBATIM))),
                Ensure(
                    new EndClauseMissingException(VERBATIM),
                    Sequence(
                            OpenCode(),
                            SpecificKeyword(JtwigKeyword.ENDVERBATIM),
                            CloseCode()
                    )
                )
        );
    }

    protected Rule AddToContent(Rule innerRule) {
        return Sequence(
                innerRule,
                ((Content) peek(1)).add(pop())
        );
    }

    protected Rule NormalTemplate() {
        return Sequence(
                Spacing(),
                Content(),
                push(new JtwigRootDocument((Content) pop())),
                EOI
        );
    }

    protected Rule ExtendingTemplate() {
        return Sequence(
                Spacing(),
                ExtendsExpression(),
                push(new JtwigExtendsDocument((ExtendsExpression) pop())),
                ZeroOrMore(
                        Spacing(),
                        BlockExpression(),
                        ((JtwigExtendsDocument) peek(1)).add((BlockExpression) pop())
                ),
                Spacing(),
                Ensure(
                        new ExtendMayOnlyBlocksException(),
                        EOI
                )
        );
    }

    protected Rule BlockExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(BLOCK),
                Identifier(),
                Spacing(),
                push(new BlockExpression((String) pop())),
                CloseCode(),
                Content(),
                (((BlockExpression) peek(1)).setContent((Content) pop())),
                Ensure(
                        new EndClauseMissingException(BLOCK),
                        OpenCode(),
                        SpecificKeyword(ENDBLOCK),
                        Spacing(),
                        CloseCode()
                )
        );
    }

    protected Rule ExtendsExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(EXTENDS),
                StringLiteral(),
                push(new ExtendsExpression((String) pop())),
                Spacing(),
                Ensure(
                        new EndClauseMissingException(EXTENDS),
                        CloseCode()
                )
        );
    }

    protected Rule IncludeExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(INCLUDE),
                StringLiteral(),
                push(new IncludeExpression((String) pop())),
                Spacing(),
                Ensure(
                        new EndClauseMissingException(INCLUDE),
                        CloseCode()
                )
        );
    }

    protected Rule TextExpression() {
        return Sequence(
                push(new Text()),
                OneOrMore(
                        FirstOf(
                                Sequence("{#", ZeroOrMore(TestNot("#}"), ANY), "#}"),
                                Sequence(
                                        Escape(),
                                        ((Text) peek()).append(match())
                                ),
                                Sequence(
                                        TestNot(
                                                FirstOf(
                                                        Symbol(OPEN_FAST),
                                                        Symbol(OPEN_CODE)
                                                )
                                        ),
                                        ANY,
                                        ((Text) peek()).append(match())
                                )
                        )
                ).suppressSubnodes()
        );
    }

    protected Rule TextExpression(Rule until) {
        return Sequence(
                push(new Text()),
                OneOrMore(
                        FirstOf(
                                Sequence(
                                        Escape(),
                                        ((Text) peek()).append(match())
                                ),
                                Sequence(
                                        TestNot(
                                                until
                                        ),
                                        ANY,
                                        ((Text) peek()).append(match())
                                )
                        )
                ).suppressSubnodes()
        );
    }

    protected Rule IfExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(IF),
                Spacing(),
                Ensure(
                        new ExpectingExpressionException(),
                        Expression()
                ),
                Spacing(),
                Ensure(
                        new EndCodeMissingException(IF),
                        CloseCode()
                ),
                push(new IfExpression(pop())),
                Content(),
                ((IfExpression) peek(1)).setContent((Content) pop()),
                ZeroOrMore(
                        Sequence(
                                OpenCode(),
                                SpecificKeyword(ELSEIF),
                                Spacing(),
                                Expression(),
                                Spacing(),
                                push(new ElseIfExpression(pop())),
                                CloseCode(),
                                Content(),
                                ((ElseIfExpression) peek(1)).setContent((Content) pop()),
                                ((IfExpression) peek(1)).addElseIf((ElseIfExpression) pop())
                        )
                ),
                Optional(
                        Sequence(
                                OpenCode(),
                                SpecificKeyword(ELSE),
                                Spacing(),
                                CloseCode(),
                                Content(),
                                ((IfExpression) peek(1)).setElseExpression(new ElseExpression((Content) pop()))
                        )
                ),
                Ensure(
                        new EndClauseMissingException(IF),
                        OpenCode(),
                        SpecificKeyword(ENDIF),
                        Spacing(),
                        CloseCode()
                )
        );
    }

    protected Rule ForExpression() {
        return FirstOf(
                ForEachMapExpression(),
                ForEachExpression()
        );
    }

    protected Rule ForEachExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(FOR),
                Variable(),
                Spacing(),
                SpecificKeyword(JtwigKeyword.IN),
                Expression(),
                Spacing(),
                push(new ForExpression((Variable) pop(1), pop())),
                Optional(
                        ForFilters()
                ),
                CloseCode(),
                Content(),
                ((ForExpression) peek(1)).setContent((Content) pop()),
                Ensure(
                        new EndCodeMissingException(FOR),
                        OpenCode(),
                        SpecificKeyword(ENDFOR),
                        CloseCode()
                )
        );
    }


    protected Rule ForEachMapExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(FOR),
                Variable(),
                Spacing(),
                FreeSymbol(COMMA),
                Variable(),
                Spacing(),
                SpecificKeyword(JtwigKeyword.IN),
                Expression(),
                Spacing(),
                push(new ForPairExpression((Variable) pop(2), (Variable) pop(1), pop())),
                Optional(
                        ForFilters()
                ),
                CloseCode(),
                Content(),
                ((ForPairExpression) peek(1)).setContent((Content) pop()),
                Ensure(
                        new EndCodeMissingException(FOR),
                        OpenCode(),
                        SpecificKeyword(ENDFOR),
                        CloseCode()
                )
        );
    }

    Rule ForFilters() {
        return ZeroOrMore(
                Sequence(
                        FreeSymbol(PIPE),
                        SpecificKeyword(FILTER),
                        Expression(),
                        ((ForExpression) peek(1)).add((FunctionElement) pop())
                )
        );
    }

    protected Rule SetExpression() {
        return Sequence(
                OpenCode(),
                SpecificKeyword(JtwigKeyword.SET),
                Variable(),
                Spacing(),
                push(new SetExpression((Variable) pop())),
                FreeSymbol(JtwigSymbol.ATTR),
                Expression(),
                Spacing(),
                (((SetExpression) peek(1))).setAssignment(pop()),
                Ensure(
                        new EndClauseMissingException(SET),
                        CloseCode()
                )
        );
    }

    protected Rule FastExpression() {
        return Sequence(
                Symbol(JtwigSymbol.OPEN_FAST),
                Spacing(),
                Expression(),
                Spacing(),
                push(new FastExpression(pop())),
                Ensure(
                        new EndClauseMissingException(JtwigSymbol.OPEN_FAST),
                        Symbol(JtwigSymbol.CLOSE_FAST)
                )
        );
    }

    // Boolean Grammar
    protected Rule Expression() {
        return Sequence(
                SpecificJtwigOperators(),
                push(simplify(pop()))
        );
    }



    protected Rule SpecificJtwigOperators () {
        return BinaryOperation(
                OrExpression(),
                Operator.STARTS_WITH,
                Operator.ENDS_WITH,
                Operator.MATCHES,
                Operator.IN
        );
    }

    protected Rule OrExpression() {
        return BinaryOperation(
                AndExpression(),
                Operator.OR
        );
    }

    Rule AndExpression() {
        return BinaryOperation(
                EqualityExpression(),
                Operator.AND
        );
    }

    Rule EqualityExpression() {
        return BinaryOperation(
                RelationalExpression(),
                Operator.EQUAL,
                Operator.DIFF
        );
    }

    protected Rule RelationalExpression() {
        return BinaryOperation(
                FirstOf(
                        NotExpression(),
                        Addition()
                ),
                Operator.LTE,
                Operator.GTE,
                Operator.LT,
                Operator.GT
        );
    }

    protected Rule NotExpression() {
        return UnaryOperation(
                Addition(),
                Operator.NOT
        );
    }


    // Math Grammar
    protected Rule Addition() {
        return BinaryOperation(
                Multiplication(),
                Operator.ADD,
                Operator.SUB
        );
    }

    protected Rule Multiplication() {
        return BinaryOperation(
                ExtendedPrimary(),
                Operator.INT_DIV,
                Operator.INT_TIMES,
                Operator.TIMES,
                Operator.DIV,
                Operator.MOD
        );
    }

    protected Rule ExtendedPrimary () {
        return FirstOf(
                TernaryExpression(),
                Primary()
        );
    }

    protected Rule TernaryExpression () {
        return Sequence(
                Primary(),
                push(new IfTernaryOperator(pop())),
                Spacing(),
                FreeSymbol(QUESTION),
                Expression(),
                Spacing(),
                ((IfTernaryOperator) peek(1)).setIfTrueExpression(pop()),
                FreeSymbol(DIV),
                Expression(),
                Spacing(),
                ((IfTernaryOperator) peek(1)).setIfFalseExpression(pop())

        );
    }

    // Basic Grammar and Help methods
    protected Rule UnaryOperation(Rule innerRule, Operator... operatorSymbols) {
        return Sequence(
                Save(FirstSymbolOf(false, operatorSymbols)),
                Spacing(),
                push(new OperationUnary()),
                ((OperationUnary) peek()).setOperator(Operator.fromString((String) pop(1))),
                innerRule,
                ((OperationUnary) peek(1)).setOperand(simplify(pop()))
        );
    }


    protected Rule BinaryOperation(Rule innerExpression, Operator... operatorSymbols) {
        return Sequence(
                innerExpression,
                Spacing(),
                push(new OperationBinary(simplify(pop()))),
                ZeroOrMore(
                        Sequence(
                                Save(FirstSymbolOf(false, operatorSymbols)),
                                Spacing(),
                                innerExpression,
                                ((OperationBinary) peek(2)).addOperator(Operator.fromString((String) pop(1))),
                                ((OperationBinary) peek(1)).add(simplify(pop()))
                        )
                )
        );
    }

    @SuppressNode
    protected Rule FirstSymbolOf(boolean spacing, Operator... operators) {
        Rule[] rules = new Rule[operators.length];
        int i = 0;
        for (Operator operator : operators)
            rules[i++] = Terminal(operator.toString(), spacing);
        return FirstOf(rules);
    }

    protected Rule Save(Rule rule) {
        return Sequence(
                rule,
                push(match())
        );
    }

    protected Rule Primary() {
        return FirstOf(
                Selection(),
                Composition(),
                BasicExpression(),
                Sequence(
                        FreeSymbol(OPEN_PARENT),
                        Expression(),
                        Spacing(),
                        Symbol(CLOSE_PARENT)
                )
        );
    }

    protected Rule Composition() {
        return Sequence(
                BasicExpression(),
                push(new Composition(pop())),
                OneOrMore(
                        Sequence(
                                Spacing(),
                                FreeSymbol(PIPE),
                                FirstOf(
                                        Function(),
                                        Variable()
                                ),
                                ((Composition) peek(1)).add(pop())
                        )
                )
        );
    }

    protected Rule Selection() {
        return Sequence(
                BasicExpression(),
                push(new Selection(pop())),
                OneOrMore(
                        Sequence(
                                Spacing(),
                                FreeSymbol(DOT),
                                DeclaredExpression(),
                                ((Selection) peek(1)).add(pop())
                        )
                )
        );
    }

    protected Rule BasicExpression() {
        return FirstOf(
                NativeExpression(),
                DeclaredExpression()
        );
    }

    protected Rule NativeExpression() {
        return FirstOf(
                ListExpression(),
                MapExpression(),
                StringLiteral(),
                Boolean(),
                Double(),
                Integer(),
                Null()
        );
    }

    protected Rule DeclaredExpression() {
        return FirstOf(
                MapSelection(),
                Function(),
                Variable()
        );
    }

    Rule MapSelection() {
        return Sequence(
                Variable(),
                FreeSymbol(JtwigSymbol.OPEN_BRACKET),
                StringLiteral(),
                Spacing(),
                FreeSymbol(JtwigSymbol.CLOSE_BRACKET),
                push(new MapSelection((Variable) pop(1), (String) pop()))
        );
    }

    protected Rule Function() {
        return FirstOf(
                FunctionWithBrackets(),
                FunctionWithoutBrackets()
        );
    }

    protected Rule FunctionWithBrackets() {
        return Sequence(
                Identifier(),
                Spacing(),
                push(new FunctionElement((String) pop())),
                FreeSymbol(OPEN_PARENT),
                Optional(Arguments()),
                FreeSymbol(CLOSE_PARENT)
        );
    }
    protected Rule FunctionWithoutBrackets() {
        return Sequence(
                Identifier(),
                Spacing(),
                push(new FunctionElement((String) pop())),
                Primary(),
                (((FunctionElement) peek(1)).add(pop()))
        );
    }

    protected Rule Arguments() {
        return Sequence(
                Primary(),
                Spacing(),
                ((Argumentable) peek(1)).add(pop()),
                ZeroOrMore(
                        FreeSymbol(COMMA),
                        Primary(),
                        Spacing(),
                        ((Argumentable) peek(1)).add(pop())
                )
        );
    }

    /**
     * Pushes a MAP
     *
     * @return
     */
    Rule MapExpression() {
        return Sequence(
                FreeSymbol(OPEN_CURLY_BRACKET),
                push(new ElementMap()),
                Optional(
                        Identifier(),
                        Spacing(),
                        FreeSymbol(DIV),
                        BasicExpression(),
                        Spacing(),
                        ((ElementMap) peek(2)).add((String) pop(1), pop()),
                        ZeroOrMore(
                                FreeSymbol(COMMA),
                                Identifier(),
                                Spacing(),
                                FreeSymbol(DIV),
                                BasicExpression(),
                                Spacing(),
                                ((ElementMap) peek(2)).add((String) pop(1), pop())
                        )
                ),
                FreeSymbol(CLOSE_CURLY_BRACKET)
        );
    }


    protected Rule ComprehensionListExpression () {
        return FirstOf(
                ComprehensionIntegerListExpression(),
                ComprehensionCharacterListExpression()
        );
    }


    protected Rule ComprehensionIntegerListExpression() {
        return Sequence(
                Integer(),
                Symbol(TWO_DOTS),
                Integer(),
                push(new IntegerList((Integer) pop(1), (Integer) pop()))
        );
    }

    protected Rule ComprehensionCharacterListExpression() {
        return Sequence(
                Char(),
                Symbol(TWO_DOTS),
                Char(),
                push(new CharacterList((Character) pop(1), (Character) pop()))
        );
    }

    protected Rule EnumerationList () {
        return Sequence(
                FreeSymbol(OPEN_BRACKET),
                push(new ValueList()),
                Optional(
                        Expression(),
                        Spacing(),
                        ((ElementList) peek(1)).add(pop()),
                        ZeroOrMore(
                                FreeSymbol(COMMA),
                                Expression(),
                                Spacing(),
                                ((ElementList) peek(1)).add(pop())
                        )
                ),
                FreeSymbol(CLOSE_BRACKET)
        );
    }

    protected Rule ListExpression() {
        return FirstOf(
                ComprehensionListExpression(),
                EnumerationList()
        );
    }

    /**
     * Pushes a boolean
     *
     * @return
     */
    protected Rule Boolean() {
        return FirstOf(
                Sequence(
                        SpecificKeyword(TRUE),
                        push(true)
                ),
                Sequence(
                        SpecificKeyword(FALSE),
                        push(false)
                )
        );
    }

    protected Rule Null() {
        return Sequence(
                SpecificKeyword(JtwigKeyword.NULL),
                push(null)
        );
    }

    /**
     * Pushes the integer (as integer)
     *
     * @return
     */
    protected Rule Integer() {
        return Sequence(
                Sequence(
                        Optional(Symbol(MINUS)),
                        OneOrMore(Digit())
                ),
                push(Integer.parseInt(match()))
        );
    }


    protected Rule Char() {
        return Sequence(
                Symbol(QUOTE),
                CharOnly(),
                push(match().charAt(0)),
                Symbol(QUOTE)
        );
    }


    /**
     * Pushes the integer (as integer)
     *
     * @return
     */
    protected Rule Double() {
        return Sequence(
                Sequence(
                        Optional(Symbol(MINUS)),
                        OneOrMore(Digit()),
                        Symbol(DOT),
                        OneOrMore(Digit())
                ),
                push(Double.valueOf(match()))
        );
    }

    Rule Variable() {
        return Sequence(
                Identifier(),
                push(new Variable((String) pop()))
        );
    }

    protected Rule CloseCode() {
        return Symbol(JtwigSymbol.CLOSE_CODE);
    }

    protected Rule OpenCode() {
        return FreeSymbol(JtwigSymbol.OPEN_CODE);
    }

    /**
     * Pushes the String (without quotes)
     *
     * @return
     */
    protected Rule StringLiteral() {
        return FirstOf(
                Sequence(
                        '"',
                        ZeroOrMore(
                                FirstOf(
                                        Escape(),
                                        Sequence(TestNot(AnyOf("\r\n\"\\")), ANY)
                                )
                        ).suppressSubnodes(),
                        push(match()),
                        '"'
                ),
                Sequence(
                        "'",
                        ZeroOrMore(
                                FirstOf(
                                        Escape(),
                                        Sequence(TestNot(AnyOf("\r\n'\\")), ANY)
                                )
                        ).suppressSubnodes(),
                        push(match()),
                        "'"
                )
        );
    }

    Rule Escape() {
        return Sequence('\\', FirstOf(AnyOf("btnfr\"\'\\"), OctalEscape(), UnicodeEscape()));
    }

    Rule OctalEscape() {
        return FirstOf(
                Sequence(CharRange('0', '3'), CharRange('0', '7'), CharRange('0', '7')),
                Sequence(CharRange('0', '7'), CharRange('0', '7')),
                CharRange('0', '7')
        );
    }

    Rule UnicodeEscape() {
        return Sequence(OneOrMore('u'), HexDigit(), HexDigit(), HexDigit(), HexDigit());
    }

    Rule HexDigit() {
        return FirstOf(CharRange('a', 'f'), CharRange('A', 'F'), CharRange('0', '9'));
    }

    Rule Digit() {
        return CharRange('0', '9');
    }

    /**
     * Pushes the identifier as String
     *
     * @return
     */
    @SuppressSubnodes
    @MemoMismatches
    protected Rule Identifier() {
        return Sequence(
                IdentifierExpression(),
                push(match())
        );
    }

    protected Rule IdentifierExpression() {
        return Sequence(
                TestNot(
                        Keyword()
                ),
                Letter(),
                ZeroOrMore(LetterOrDigit())
        );
    }

    @SuppressNode
    protected Rule Symbol(JtwigSymbol symbol) {
        return Terminal(symbol.getSymbol(), false);
    }

    @SuppressNode
    protected Rule FreeSymbol(JtwigSymbol symbol) {
        return Sequence(
                Terminal(symbol.getSymbol(), false),
                Optional(Spacing())
        );
    }

    @SuppressNode
    protected Rule SpecificKeyword(JtwigKeyword keyword) {
        return Terminal(keyword.getKeyword(), LetterOrDigit());
    }

    @MemoMismatches
    protected Rule Keyword() {
        return Sequence(
                FirstOf(JtwigKeyword.keywords()),
                TestNot(LetterOrDigit())
        );
    }

    protected Rule CharOnly () {
        return FirstOf(
                CharRange('a', 'z'),
                CharRange('A', 'Z')
        );
    }

    protected Rule Letter() {
        // switch to this "reduced" character space version for a ~10% parser performance speedup
        return FirstOf(CharRange('a', 'z'), CharRange('A', 'Z'), '_', '$');
    }

    @MemoMismatches
    protected Rule LetterOrDigit() {
        return FirstOf(CharRange('a', 'z'), CharRange('A', 'Z'), CharRange('0', '9'), '_', '$');
    }

    @SuppressNode
    @DontLabel
    protected Rule Terminal(String string) {
        return Sequence(string, Spacing()).label('\'' + string + '\'');
    }

    @SuppressNode
    @DontLabel
    protected Rule Terminal(String string, boolean spacing) {
        if (spacing)
            return Sequence(string, Spacing()).label('\'' + string + '\'');
        else
            return String(string).label('\'' + string + '\'');
    }

    @SuppressNode
    @DontLabel
    protected Rule Terminal(String string, Rule mustNotFollow) {
        return Sequence(string, TestNot(mustNotFollow), Spacing()).label('\'' + string + '\'');
    }

    @SuppressNode
    protected Rule Spacing() {
        return ZeroOrMore(FirstOf(

                // whitespace
                OneOrMore(AnyOf(" \t\r\n\f").label("Whitespace")),

                // traditional comment
                Sequence("{#", ZeroOrMore(TestNot("#}"), ANY), "#}")
        ));
    }
}
