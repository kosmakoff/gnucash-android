/*
 * Copyright (c) 2012 - 2014 Ngewi Fet <ngewif@gmail.com>
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

package org.gnucash.android.model;

import org.gnucash.android.app.GnuCashApplication;
import org.gnucash.android.db.AccountsDbAdapter;
import org.gnucash.android.export.ofx.OfxHelper;
import org.gnucash.android.export.qif.QifHelper;
import org.gnucash.android.model.Transaction.TransactionType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * An account represents a transaction account in with {@link Transaction}s may be recorded
 * Accounts have different types as specified by {@link AccountType} and also a currency with
 * which transactions may be recorded in the account
 * By default, an account is made an {@link AccountType#CASH} and the default currency is
 * the currency of the Locale of the device on which the software is running. US Dollars is used
 * if the platform locale cannot be determined.
 * 
 * @author Ngewi Fet <ngewif@gmail.com>
 * @see AccountType
 */
public class Account {

	/**
	 * The MIME type for accounts in GnucashMobile
	 * This is used when sending intents from third-party applications
	 */
	public static final String MIME_TYPE = "vnd.android.cursor.item/vnd.org.gnucash.android.account";

    /*
        ^             anchor for start of string
        #             the literal #
        (             start of group
        ?:            indicate a non-capturing group that doesn't generate backreferences
        [0-9a-fA-F]   hexadecimal digit
        {3}           three times
        )             end of group
        {1,2}         repeat either once or twice
        $             anchor for end of string
     */
    /**
     * Regular expression for validating color code strings.
     * Accepts #rgb and #rrggbb
     */
    //TODO: Allow use of #aarrggbb format as well
    public static final String COLOR_HEX_REGEX = "^#(?:[0-9a-fA-F]{3}){1,2}$";

    /**
	 * The type of account
	 * This are the different types specified by the OFX format and 
	 * they are currently not used except for exporting
	 */
	public enum AccountType {
        CASH(TransactionType.DEBIT), BANK, CREDIT, ASSET(TransactionType.DEBIT), LIABILITY, INCOME,
        EXPENSE(TransactionType.DEBIT), PAYABLE, RECEIVABLE, EQUITY, CURRENCY, STOCK, MUTUAL, ROOT;

        /**
         * Indicates that this type of normal balance the account type has
         * <p>To increase the value of an account with normal balance of credit, one would credit the account.
         * To increase the value of an account with normal balance of debit, one would likewise debit the account.</p>
         */
        private TransactionType mNormalBalance = TransactionType.CREDIT;

        private AccountType(TransactionType normalBalance){
            this.mNormalBalance = normalBalance;
        }

        private AccountType() {
            //nothing to see here, move along
        }

        public boolean hasDebitNormalBalance(){
            return mNormalBalance == TransactionType.DEBIT;
        }

        /**
         * Returns the type of normal balance this account possesses
         * @return TransactionType balance of the account type
         */
        public TransactionType getNormalBalanceType(){
            return mNormalBalance;
        }
    }

    /**
     * Accounts types which are used by the OFX standard
     */
	public enum OfxAccountType {CHECKING, SAVINGS, MONEYMRKT, CREDITLINE }

    /**
	 * Unique Identifier of the account
	 * It is generated when the account is created and can be set a posteriori as well
	 */
	private String mUID;
	
	/**
	 * Name of this account
	 */
	private String mName;
	
	/**
	 * Currency used by transactions in this account
	 */
	private Currency mCurrency; 
	
	/**
	 * Type of account
	 * Defaults to {@link AccountType#CASH}
	 */
	private AccountType mAccountType = AccountType.CASH;
	
	/**
	 * List of transactions in this account
	 */
	private List<Transaction> mTransactionsList = new ArrayList<Transaction>();

	/**
	 * Account UID of the parent account. Can be null
	 */
	private String mParentAccountUID;

    /**
     * Save UID of a default account for transfers.
     * All transactions in this account will by default be transfers to the other account
     */
    private String mDefaultTransferAccountUID;

    /**
     * Flag for placeholder accounts.
     * These accounts cannot have transactions
     */
    private boolean mPlaceholderAccount;

    /**
     * Account color field in hex format #rrggbb
     */
    private String mColorCode;

    /**
     * Flag which marks this account as a favorite account
     */
    private boolean mIsFavorite;

	/**
	 * An extra key for passing the currency code (according ISO 4217) in an intent
	 */
	public static final String EXTRA_CURRENCY_CODE 	= "org.gnucash.android.extra.currency_code";
	
	/**
	 * Extra key for passing the unique ID of the parent account when creating a 
	 * new account using Intents
	 */
	public static final String EXTRA_PARENT_UID 	= "org.gnucash.android.extra.parent_uid";
	
	/**
	 * Constructor
	 * Creates a new account with the default currency and a generated unique ID
	 * @param name Name of the account
	 */
	public Account(String name) {
		setName(name);
		this.mUID = generateUID();
		this.mCurrency = Currency.getInstance(Money.DEFAULT_CURRENCY_CODE);
	}
	
	/**
	 * Overloaded constructor
	 * @param name Name of the account
	 * @param currency {@link Currency} to be used by transactions in this account
	 */
	public Account(String name, Currency currency){
		setName(name);
		this.mUID = generateUID();
		this.mCurrency = currency;
	}

	/**
	 * Sets the name of the account
	 * @param name String name of the account
	 */
	public void setName(String name) {
		this.mName = name.trim();
	}

	/**
	 * Returns the name of the account
	 * @return String containing name of the account
	 */
	public String getName() {
		return mName;
	}
	
	/**
	 * Generates a unique ID for the account based on the name and a random string. 
	 * This represents the ACCTID in the exported OFX and should have a maximum of 22 alphanumeric characters
	 * @return Generated Unique ID string
	 */
	protected String generateUID(){
		String uuid = UUID.randomUUID().toString();
		
		if (mName == null || mName.length() == 0){
			//if we do not have a name, return pure random
			return uuid.substring(0, 22);
		}
		
		uuid = uuid.substring(uuid.lastIndexOf("-"));
		String name = mName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
		if (name.length() > 9)
			name = name.substring(0, 10);
		uuid = name + uuid;		
		return uuid;
	}
	
	/**
	 * Returns the unique ID of this account
	 * @return String containing unique ID for the account
	 */
	public String getUID(){
		return mUID;
	}
	
	/**
	 * Sets the unique identifier of this acocunt
	 * @param uid Unique identifier to be set
	 */
	public void setUID(String uid){
		this.mUID = uid;
	}
	
	/**
	 * Get the type of account
	 * @return {@link AccountType} type of account
	 */
	public AccountType getAccountType() {
		return mAccountType;
	}

	/**
	 * Sets the type of account
	 * @param mAccountType Type of account
	 * @see AccountType
	 */
	public void setAccountType(AccountType mAccountType) {
		this.mAccountType = mAccountType;
	}

	/**
	 * Adds a transaction to this account
	 * <p>The currency of the transaction will be set to the currency of the account
	 * if they are not the same. The currency value conversion is performed, just 
	 * a different currency is assigned to the same value amount in the transaction.</p>
	 * <p>
	 * If the transaction has no account Unique ID, it will be set to the UID of this account.
	 * Some transactions already have the account UID and double account UID set. In that case,
	 * nothing is changed
	 * </p>
	 * @param transaction {@link Transaction} to be added to the account
	 */
	public void addTransaction(Transaction transaction){
		//some double transactions may already an account UID. Set only for those with null
		if (transaction.getAccountUID() == null)
			transaction.setAccountUID(getUID());
		transaction.setCurrency(mCurrency);
		mTransactionsList.add(transaction);
	}
	
	/**
	 * Sets a list of transactions for this account.
	 * Overrides any previous transactions with those in the list.
	 * The account UID and currency of the transactions will be set to the unique ID 
	 * and currency of the account respectively
	 * @param transactionsList List of {@link Transaction}s to be set.
	 */
	public void setTransactions(List<Transaction> transactionsList){
		for (Transaction transaction : transactionsList) {
			if (transaction.getAccountUID() == null)
				transaction.setAccountUID(getUID());
			transaction.setCurrency(mCurrency);
		}
		this.mTransactionsList = transactionsList;
	}
		
	/**
	 * Removes <code>transaction</code> from this account
	 * @param transaction {@link Transaction} to be removed from account
	 */
	public void removeTransaction(Transaction transaction){
		mTransactionsList.remove(transaction);
	}
	
	/**
	 * Returns a list of transactions for this account
	 * @return Array list of transactions for the account
	 */
	public List<Transaction> getTransactions(){
		return mTransactionsList;
	}
	
	/**
	 * Returns the number of transactions in this account
	 * @return Number transactions in account
	 */
	public int getTransactionCount(){
		return mTransactionsList.size();
	}
	
	/**
	 * Returns true if there is at least one transaction in the account
	 * which has not yet been exported
	 * @return <code>true</code> if there are unexported transactions, <code>false</code> otherwise.
	 */
	public boolean hasUnexportedTransactions(){
		for (Transaction transaction : mTransactionsList) {
			if (transaction.isExported() == false)
				return true;			
		}
		return false;
	}
	
	/**
	 * Returns the aggregate of all transactions in this account.
	 * It takes into account debit and credit amounts, it does not however consider sub-accounts
	 * @return {@link Money} aggregate amount of all transactions in account.
	 */
	public Money getBalance(){
		//TODO: Consider double entry transactions
		Money balance = new Money(new BigDecimal(0), this.mCurrency);
		for (Transaction transaction : mTransactionsList) {
			balance = balance.add(transaction.getAmount());
		}
		return balance;
	}

    /**
     * Returns the color code of the account in the format #rrggbb
     * @return Color code of the account
     */
    public String getColorHexCode() {
        return mColorCode;
    }

    /**
     * Sets the color code of the account.
     * @param colorCode Color code to be set in the format #rrggbb or #rgb
     * @throws java.lang.IllegalArgumentException if the color code is not properly formatted
     */
    public void setColorCode(String colorCode) {
        if (colorCode == null)
            return;

        if (!Pattern.matches(COLOR_HEX_REGEX, colorCode))
            throw new IllegalArgumentException("Invalid color hex code");

        this.mColorCode = colorCode;
    }

    /**
     * Tests if this account is a favorite account or not
     * @return <code>true</code> if account is flagged as favorite, <code>false</code> otherwise
     */
    public boolean isFavorite() {
        return mIsFavorite;
    }

    /**
     * Toggles the favorite flag on this account on or off
     * @param isFavorite <code>true</code> if account should be flagged as favorite, <code>false</code> otherwise
     */
    public void setFavorite(boolean isFavorite) {
        this.mIsFavorite = isFavorite;
    }

    /**
	 * @return the mCurrency
	 */
	public Currency getCurrency() {
		return mCurrency;
	}

	/**
	 * Sets the currency to be used by this account
	 * @param mCurrency the mCurrency to set
	 */
	public void setCurrency(Currency mCurrency) {		
		this.mCurrency = mCurrency;
		//TODO: Maybe at some time t, this method should convert all 
		//transaction values to the corresponding value in the new currency
	}

	/**
	 * Sets the Unique Account Identifier of the parent account
	 * @param parentUID String Unique ID of parent account
	 */
	public void setParentUID(String parentUID){
		mParentAccountUID = parentUID;
	}
	
	/**
	 * Returns the Unique Account Identifier of the parent account
	 * @return String Unique ID of parent account
	 */
	public String getParentUID() {
		return mParentAccountUID;
		
	}

    /**
     * Returns <code>true</code> if this account is a placeholder account, <code>false</code> otherwise.
     * @return <code>true</code> if this account is a placeholder account, <code>false</code> otherwise
     */
    public boolean isPlaceholderAccount(){
        return mPlaceholderAccount;
    }

    /**
     * Sets the placeholder flag for this account.
     * Placeholder accounts cannot have transactions
     * @param isPlaceholder Boolean flag indicating if the account is a placeholder account or not
     */
    public void setPlaceHolderFlag(boolean isPlaceholder){
        mPlaceholderAccount = isPlaceholder;
    }

    /**
     * Return the unique ID of accounts to which to default transfer transactions to
     * @return Unique ID string of default transfer account
     */
    public String getDefaultTransferAccountUID() {
        return mDefaultTransferAccountUID;
    }

    /**
     * Set the unique ID of account which is the default transfer target
     * @param defaultTransferAccountUID Unique ID string of default transfer account
     */
    public void setDefaultTransferAccountUID(String defaultTransferAccountUID) {
        this.mDefaultTransferAccountUID = defaultTransferAccountUID;
    }


    /**
	 * Maps the <code>accountType</code> to the corresponding account type.
	 * <code>accountType</code> have corresponding values to GnuCash desktop
	 * @param accountType {@link AccountType} of an account
	 * @return Corresponding {@link OfxAccountType} for the <code>accountType</code>
	 * @see AccountType
	 * @see OfxAccountType
	 */
	public static OfxAccountType convertToOfxAccountType(AccountType accountType){
		switch (accountType) {
		case CREDIT:
		case LIABILITY:
			return OfxAccountType.CREDITLINE;
			
		case CASH:
		case INCOME:
		case EXPENSE:
		case PAYABLE:
		case RECEIVABLE:
			return OfxAccountType.CHECKING;
			
		case BANK:
		case ASSET:
			return OfxAccountType.SAVINGS;
			
		case MUTUAL:
		case STOCK:
		case EQUITY:
		case CURRENCY:
			return OfxAccountType.MONEYMRKT;

		default:
			return OfxAccountType.CHECKING;
		}
	}
	
	/**
	 * Converts this account's transactions into XML and adds them to the DOM document
	 * @param doc XML DOM document for the OFX data
	 * @param parent Parent node to which to add this account's transactions in XML
	 */
	public void toOfx(Document doc, Element parent, boolean exportAllTransactions){
		Element currency = doc.createElement(OfxHelper.TAG_CURRENCY_DEF);
		currency.appendChild(doc.createTextNode(mCurrency.getCurrencyCode()));						
		
		//================= BEGIN BANK ACCOUNT INFO (BANKACCTFROM) =================================
		
		Element bankId = doc.createElement(OfxHelper.TAG_BANK_ID);
		bankId.appendChild(doc.createTextNode(OfxHelper.APP_ID));
		
		Element acctId = doc.createElement(OfxHelper.TAG_ACCOUNT_ID);
		acctId.appendChild(doc.createTextNode(mUID));
		
		Element accttype = doc.createElement(OfxHelper.TAG_ACCOUNT_TYPE);
		String ofxAccountType = convertToOfxAccountType(mAccountType).toString();
		accttype.appendChild(doc.createTextNode(ofxAccountType));
		
		Element bankFrom = doc.createElement(OfxHelper.TAG_BANK_ACCOUNT_FROM);
		bankFrom.appendChild(bankId);
		bankFrom.appendChild(acctId);
		bankFrom.appendChild(accttype);
		
		//================= END BANK ACCOUNT INFO ============================================
		
		
		//================= BEGIN ACCOUNT BALANCE INFO =================================
		String balance = getBalance().toPlainString();
		String formattedCurrentTimeString = OfxHelper.getFormattedCurrentTime();
		
		Element balanceAmount = doc.createElement(OfxHelper.TAG_BALANCE_AMOUNT);
		balanceAmount.appendChild(doc.createTextNode(balance));			
		Element dtasof = doc.createElement(OfxHelper.TAG_DATE_AS_OF);
		dtasof.appendChild(doc.createTextNode(formattedCurrentTimeString));
		
		Element ledgerBalance = doc.createElement(OfxHelper.TAG_LEDGER_BALANCE);
		ledgerBalance.appendChild(balanceAmount);
		ledgerBalance.appendChild(dtasof);
		
		//================= END ACCOUNT BALANCE INFO =================================
		
		
		//================= BEGIN TIME PERIOD INFO =================================
		
		Element dtstart = doc.createElement(OfxHelper.TAG_DATE_START);
		dtstart.appendChild(doc.createTextNode(formattedCurrentTimeString));
		
		Element dtend = doc.createElement(OfxHelper.TAG_DATE_END);
		dtend.appendChild(doc.createTextNode(formattedCurrentTimeString));
		
		//================= END TIME PERIOD INFO =================================
		
		
		//================= BEGIN TRANSACTIONS LIST =================================
		Element bankTransactionsList = doc.createElement(OfxHelper.TAG_BANK_TRANSACTION_LIST);
		bankTransactionsList.appendChild(dtstart);
		bankTransactionsList.appendChild(dtend);
		
		for (Transaction transaction : mTransactionsList) {
			if (!exportAllTransactions && transaction.isExported())
				continue;
			
			bankTransactionsList.appendChild(transaction.toOfx(doc, mUID));
		}		
		//================= END TRANSACTIONS LIST =================================
					
		Element statementTransactions = doc.createElement(OfxHelper.TAG_STATEMENT_TRANSACTIONS);
		statementTransactions.appendChild(currency);
		statementTransactions.appendChild(bankFrom);
		statementTransactions.appendChild(bankTransactionsList);
		statementTransactions.appendChild(ledgerBalance);
		
		parent.appendChild(statementTransactions);
				
	}

    /**
     * Exports the account info and transactions in the QIF format
     * @param exportAllTransactions Flag to determine whether to export all transactions, or only new transactions since last export
     * @return QIF representation of the account information
     */
    public String toQIF(boolean exportAllTransactions) {
        StringBuffer accountQifBuffer = new StringBuffer();
        final String newLine = "\n";

        AccountsDbAdapter accountsDbAdapter = new AccountsDbAdapter(GnuCashApplication.getAppContext());
        String fullyQualifiedAccountName = accountsDbAdapter.getFullyQualifiedAccountName(mUID);
        accountsDbAdapter.close();

        accountQifBuffer.append(QifHelper.ACCOUNT_HEADER).append(newLine);
        accountQifBuffer.append(QifHelper.ACCOUNT_NAME_PREFIX).append(fullyQualifiedAccountName).append(newLine);
        accountQifBuffer.append(QifHelper.ENTRY_TERMINATOR).append(newLine);

        String header = QifHelper.getQifHeader(mAccountType);
        accountQifBuffer.append(header + newLine);

        for (Transaction transaction : mTransactionsList) {
            //ignore those which are loaded as double transactions.
            // They will be handled as splits
            if (!transaction.getAccountUID().equals(mUID))
                continue;

            if (!exportAllTransactions && transaction.isExported())
                continue;

            accountQifBuffer.append(transaction.toQIF() + newLine);
        }
        return accountQifBuffer.toString();
    }
}