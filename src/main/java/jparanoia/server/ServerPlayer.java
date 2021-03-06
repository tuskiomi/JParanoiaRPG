package jparanoia.server;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Serializable;

import static java.lang.System.exit;
import static java.lang.System.out;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.StringTokenizer;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleContext;
import static jparanoia.server.JPServer.absoluteChat;
import static jparanoia.server.JPServer.absoluteSpam;
import static jparanoia.server.JPServer.sendCommand;
import static jparanoia.server.JPServer.stripComments;

import jparanoia.server.constants.ServerConstants;
import jparanoia.shared.JPPlayer;
import static jparanoia.shared.JPSounds.CHARSHEET_ALERT;
import static jparanoia.shared.JPSounds.DEMOTED;
import static jparanoia.shared.JPSounds.PROMOTED;
import jparanoia.shared.JParanoia;
import static jparanoia.shared.JParanoia.errorMessage;
import static jparanoia.shared.JParanoia.soundIsOn;
import static jparanoia.shared.JParanoia.soundMenu;
import static jparanoia.shared.JParanoia.soundPlayer;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;

public class ServerPlayer extends JPPlayer implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1355967379808152673L;
	private final static Logger logger = getLogger(MethodHandles.lookup().lookupClass());

	static int numUnsavedCharsheets = 0;
	static FileWriter writer;
	static SimpleAttributeSet attributeSet;
	static StringTokenizer st;
	final int PLAYER_NUMBER;
	final boolean IS_PLAYER;
	int cloneNumber;
	boolean loggedIn = false;
	boolean isDead = false;
	boolean muted = false;
	boolean frozen = false;
	boolean unsavedCharsheet = false;
	PrivateMessagePane pmPane;
	StatusPanel statusPanel;
	DefaultStyledDocument characterSheet;
	Color chatColor = Color.gray;
	String dataFile;
	String data;
	ServerChatThread chatThread = null;
	BufferedReader reader;
	private boolean debugSpecific = false;
	private String name;
	private String clearance;
	public int clearanceInt;
	private String sector;
	private String password;
	//JPServer,JPClient
	public String realName;
	//JPServer
	public ServerPlayerMenu playerMenu;
	public NPCMenu npcMenu;
	private JCheckBox globalExcludeCheckBox;
	private static HashMap<String, Integer> securityClearance = new HashMap<>();

	static {
		securityClearance.put("IR", 0);
		securityClearance.put("R", 1);
		securityClearance.put("O", 2);
		securityClearance.put("Y", 3);
		securityClearance.put("G", 4);
		securityClearance.put("B", 5);
		securityClearance.put("I", 6);
		securityClearance.put("V", 7);
		securityClearance.put("U", 8);
	}

	public ServerPlayer(int playerNumber, String name, boolean isPlayer, String password, String dataFile) {
		// Apply the passed in options.
		this.PLAYER_NUMBER = playerNumber;
		this.password = password;
		this.IS_PLAYER = isPlayer;
		this.name = name;
		this.dataFile = dataFile;
		this.characterSheet = new DefaultStyledDocument(new StyleContext());

		// Check to see if this server player is an NPC.
		// If they are an NPC, they get to skip the login process.
		if (!isPlayer && this.name.startsWith(ServerConstants.DUMMY_NPC_NAME)) {
			this.loggedIn = true;
			this.npcMenu = new NPCMenu(this);
			logger.info("Generated NPCMenu for " + this.name);
			JPServer.spareNpcs.add(this);
		}

		attributeSet = JPServer.charsheetAttributes;
	}

	public String getPassword() {
		return this.password;
	}

	public void readCharacterSheetFile() {
		try {
			this.reader = new BufferedReader(new FileReader(dataFile));
		} catch (Exception localException1) {
			logger.info("An exception occured while attemping to access " + this.dataFile);
			localException1.printStackTrace();
		}
		try {
			this.data = this.reader.readLine();
			String str = null;
			try {
				str = this.data.substring(0, this.data.lastIndexOf("-"));
			} catch (Exception localException3) {
				localException3.printStackTrace();
				JParanoia.errorMessage("Invalid Charsheet", "The charsheet file \"" + this.dataFile
						+ "\" does not have\n" + "the character's name on the first line. This is mandatory.");
				System.exit(0);
			}
			String newName;
			if (this.PLAYER_NUMBER == 0) {
				if (JPServer.serverOptions.isGmNameNag()
						&& this.data.substring(0, this.data.length() - 2).equals("GM")) {
					newName = (String) JOptionPane.showInputDialog(null,
							ServerConstants.PLAYER_NAME_IS_GM_WARNING + this.dataFile
									+ ServerConstants.PLAYER_NAME_IS_GM_WARNING_DESCRIPTION,
							ServerConstants.PLAYER_NAME_IS_GM_WARNING_TITLE, JOptionPane.PLAIN_MESSAGE, null, null,
							"GM");
					if (newName != null && !"".equals(newName)) {
						this.name = newName;
					} else {
						logger.info("NUNAME == " + newName);
						this.name = this.data.substring(0, this.data.length() - 2);
					}
				} else {
					this.name = this.data.substring(0, this.data.length() - 2);
				}
			} else if (this.IS_PLAYER) {
				st = new StringTokenizer(str, "-");
				logger.info("Parsing name for: " + str);
				this.name = str.substring(0, str.indexOf("-"));
				if (st.countTokens() > 3 || st.countTokens() < 2) {
					errorMessage("Invalid Player Name",
							"The character sheet " + this.dataFile + "\n" + "attempts to define a player with\n"
									+ "an invalid name \"" + str + ServerConstants.PLAYER_NAME_ERROR_DESCRIPTION);
					exit(0);
				}
				if (st.countTokens() == 2) {
					logger.info("2 tokens in \"" + str + "\", assigning Infrared clearance.");
					this.clearance = "IR";
				} else {
					this.clearance = str.substring(str.indexOf('-') + 1, str.lastIndexOf('-'));
				}
				if ((this.clearanceInt = evaluateClearance(this.clearance)) == -99) {
					errorMessage(ServerConstants.INVALID_CLEARANCE_WARNING_TITLE,
							this.dataFile + ServerConstants.INVALID_CLEARANCE_WARNING + this.clearance
									+ ServerConstants.INVALID_CLEARANCE_WARNING_DESCRIPTION
									+ ServerConstants.INVALID_CLEARANCE_REMEDY_SERVER);
					exit(0);
				}
				this.sector = str.substring(str.lastIndexOf("-") + 1);
				if (this.sector.length() != 3) {
					errorMessage("Invalid sector",
							"The character sheet " + this.dataFile + "\n" + "attempts to define a player with\n"
									+ "invalid sector name \"" + this.sector + "\".\n" + "\n"
									+ "Sector names MUST be exactly three characters in length.\n" + "\n"
									+ "Correct the error and relaunch the server.");
					exit(0);
				}
				char[] sectorChars = this.sector.toCharArray();
				for (int i = 0; i < sectorChars.length; ++i) {
					if (sectorChars[i] < 'A' || sectorChars[i] > 'Z') {
						errorMessage("Invalid sector",
								"The character sheet " + this.dataFile + "\n" + "attempts to define a player with\n"
										+ "invalid sector name \"" + this.sector + "\".\n" + "\n"
										+ "Sector names MUST only contain capital letters A-Z.\n" + "\n"
										+ "Correct the error and relaunch the server.");
						exit(0);
					}
				}
			} else {
				this.name = this.data.substring(0, this.data.length() - 2);
			}

			// TODO: Make this *ROBUST*
			if (this.name.startsWith(ServerConstants.PLAYER_DEAD_PREFIX)) {
				this.isDead = true;
			}

			this.cloneNumber = Integer.parseInt(this.data.substring(this.data.lastIndexOf("-") + 1));

			for (newName = this.reader.readLine(); newName != null; newName = this.reader.readLine()) {
				if (!newName.startsWith("#")) {
					this.characterSheet.insertString(this.characterSheet.getLength(), newName + "\n", attributeSet);
				}
			}
			this.reader.close();
			this.characterSheet.addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent paramAnonymousDocumentEvent) {
					if (!ServerPlayer.this.unsavedCharsheet) {
						ServerPlayer.this.charsheetUpdated();
					}
				}

				public void removeUpdate(DocumentEvent paramAnonymousDocumentEvent) {
					if (!ServerPlayer.this.unsavedCharsheet) {
						ServerPlayer.this.charsheetUpdated();
					}
				}

				public void changedUpdate(DocumentEvent paramAnonymousDocumentEvent) {
				}
			});
			this.playerMenu = new ServerPlayerMenu(this);
			this.globalExcludeCheckBox = new JCheckBox(getName());
		} catch (Exception localException2) {
			logger.info("An exception occured while reading " + this.name + "'s data file.");
			localException2.printStackTrace();
			errorMessage("Exception", "An exception occured while reading " + this.name + "'s data file.\n"
					+ "Run JParanoia with the console window to view errors.\n" + "JParanoia will now terminate.");
			exit(-1);
		}
	}

	public DefaultStyledDocument getCharsheet() {
		return this.characterSheet;
	}

	public String getCharacterSheetFile() {
		return this.dataFile;
	}

	// TODO: MAKE SECURE!!!
	public boolean checkPassword(String paramString) {
		JPServer.absoluteChat(getName() + ServerConstants.PLAYER_CONNECTION_ATTEMPT_MESSAGE);
		return paramString.equalsIgnoreCase(this.password);
	}

	/*
	 * Generate the name of the player based on the character sheet. The name of the
	 * player contains three sections; The name, the clearance, and the sector. Each
	 * of these are separated by a comma.
	 */
	public String getName() {
		if (this.IS_PLAYER && this.PLAYER_NUMBER != 0) {
			if (this.clearanceInt == 0) {
				return this.name + "-" + this.sector;
			}
			return this.name + "-" + this.clearance + "-" + this.sector;
		}
		return this.name;
	}

	public void kill() {
		if (this.IS_PLAYER && !this.isDead) {
			if (this.cloneNumber < JPServer.serverOptions.getMaxNumClones() || JPServer.serverOptions.isPXPGame()) {
				String str1 = this.name;
				String str2 = deathEuphamism(this.toString());
				this.cloneNumber += 1;
				JPServer.absoluteChat(str2);
				JPServer.sendCommand("199" + str2);
			} else {
				this.isDead = true;
				JPServer.absoluteChat(this.toString() + ServerConstants.PLAYER_DEATH_NO_CLONES);
				JPServer.sendCommand("199" + this.toString() + ServerConstants.PLAYER_DEATH_NO_CLONES);
				this.name = "(dead)" + this.name;
			}
			JPServer.notifyPlayersOfDeath(this);
			JPServer.spoofComboBox.repaint();
			saveCharsheet(false);
		} else if (this.IS_PLAYER && this.isDead) {
			JPServer.absoluteChat(
					"The GM has attempted to kill " + this.name + " one more time. This is, of course, impossible.");
			JPServer.sendCommand(
					"199"+"The GM has attempted to kill " + this.name + " one more time. This is, of course, impossible.");
		}
	}

	public void unkill() {
		if (this.IS_PLAYER) {
			if (this.isDead) {
				this.isDead = false;
				JPServer.absoluteChat(toString() + " has been refunded a clone due to a clerical error.");
				JPServer.sendCommand("199" + toString() + " has been refunded a clone due to a clerical error.");
				this.name = this.name.substring(6);
				JPServer.notifyPlayersOfUndeath(this);
				JPServer.spoofComboBox.repaint();
				saveCharsheet(false);
			} else if (this.cloneNumber > 1) {
				JPServer.absoluteChat(toString() + " has been refunded a clone due to a clerical error.");
				JPServer.sendCommand("199" + toString() + " has been refunded a clone due to a clerical error.");
				this.cloneNumber -= 1;
				JPServer.notifyPlayersOfUndeath(this);
				JPServer.spoofComboBox.repaint();
				saveCharsheet(false);
			} else {
				JPServer.absoluteChat("The GM has attempted to give " + toString()
						+ " another clone. This is, of course, impossible.");
				JPServer.sendCommand("199The GM has attempted to give " + toString()
						+ " another clone. This is, of course, impossible.");
			}
		}
	}

	private String deathEuphamism(String paramString) {
		int i = JPServer.rand.nextInt(14);
		String str = paramString + " ";

		str = str + ServerConstants.DEATH_MESSAGES[i];
		return str;
	}

	void rename() {
		String str1 = getName();
		String str4 = "";
		new JOptionPane();
		String str5 = (String) JOptionPane.showInputDialog(null,
				"Enter new name for " + getName() + "\n" + "(omit the clone number):", "New Clone Family...",
				JOptionPane.PLAIN_MESSAGE, null, null, getName());
		if (str5 != null && !str5.equals("")) {
			logger.info("About to attempt name parsing on: " + str5);
			try {
				st = new StringTokenizer(str5, "-");
				if (st.countTokens() > 3) {
					errorMessage("Invalid name", "I told you to leave off the clone number.\nTry again.");
					return;
				}
				if (st.countTokens() < 2) {
					errorMessage("Invalid name",
							"You didn't give this clone a sector and/or\nsecurity clearance.\nTry again.");
					return;
				}
				String str3;
				if (st.countTokens() == 2) {
					logger.info("2 tokens in \"" + str5 + "\", assigning Infrared clearance.");
					str3 = "IR";
				} else {
					str3 = str5.substring(str5.indexOf("-") + 1, str5.lastIndexOf("-"));
				}
				String str2 = str5.substring(0, str5.indexOf("-"));
				str4 = str5.substring(str5.lastIndexOf("-") + 1);
				if (str4.length() != 3) {
					errorMessage("Invalid sector",
							"Sector names MUST consist of three capital letters A-Z.\nTry again.");
					return;
				}
				char[] arrayOfChar = str4.toCharArray();
				for (final char anArrayOfChar : arrayOfChar) {
					if (anArrayOfChar < 'A' || anArrayOfChar > 'Z') {
						errorMessage("Invalid sector", "The sector \"" + str4 + "\" is invalid.\n"
								+ "Sector names MUST only contain capital letters A-Z.\n" + "\n Try again.");
						return;
					}
				}
				int i;
				if ((i = evaluateClearance(str3)) == -99) {
					errorMessage(ServerConstants.INVALID_CLEARANCE_WARNING_TITLE,
							ServerConstants.INVALID_CLEARANCE_SEC_CLRNCE + str3
									+ ServerConstants.INVALID_CLEARANCE_INVALID
									+ ServerConstants.INVALID_CLEARANCE_WARNING_DESCRIPTION);
					return;
				}
				this.clearanceInt = i;
				this.name = str2;
				this.clearance = str3;
				this.sector = str4;
			} catch (Exception localException) {
				errorMessage("Invalid name",
						"You have entered a name incompatible\nwith the standards set forth by Friend\nComputer. Report for termination.");
			}
			sendCommand("199" + str1 + " has been replaced by " + str5);
			absoluteChat(str1 + " has been replaced by " + str5);
			String str6;
			if (this.loggedIn) {
				str6 = "y";
			} else {
				str6 = "n";
			}
			this.cloneNumber = 1;
			this.isDead = false;
			this.globalExcludeCheckBox.setText(getName());
			JPServer.repaintMenus();
			this.playerMenu.setText(getName());
			sendCommand("010" + getID() + "p" + str6 + str5 + "-" + this.cloneNumber);
			saveCharsheet(false);
			this.pmPane.reflectNameChange();
		}
	}

	void setClearance(String paramString1, String paramString2) {
		String str1 = "";
		int i = evaluateClearance(paramString1);
		if (i < this.clearanceInt) {
			str1 = "demoted";
			if (JPServer.soundIsOn && JPServer.soundMenu.promotedDemotedMenuItem.isSelected()) {
				JPServer.soundPlayer.play(DEMOTED);
			}
			JPServer.sendCommand("020");
		} else if (i > this.clearanceInt) {
			str1 = "promoted";
			if (JPServer.soundIsOn && JPServer.soundMenu.promotedDemotedMenuItem.isSelected()) {
				JPServer.soundPlayer.play(PROMOTED);
			}
			JPServer.sendCommand("021");
		} else {
			return;
		}
		JPServer.absoluteSpam(getName() + " has been " + str1 + " to " + paramString2 + " clearance!");
		this.clearance = paramString1;
		this.clearanceInt = i;
		String str2;
		if (this.loggedIn) {
			str2 = "y";
		} else {
			str2 = "n";
		}
		JPServer.repaintMenus();
		this.playerMenu.setText(getName());
		JPServer.sendCommand("010" + getID() + "p" + str2 + getName() + "-" + this.cloneNumber);
		saveCharsheet(false);
	}

	public int evaluateClearance(String clearance) {
		int clearanceInt = -99;
		if (ServerPlayer.securityClearance.containsKey(clearance)) {
			clearanceInt = ServerPlayer.securityClearance.get(clearance);
		}
		if (this.playerMenu != null && clearanceInt != -99) {
			this.playerMenu.playerClearanceMenu.securityClearancesByRank[clearanceInt].setSelected(true);
		}
		return clearanceInt;
	}

	public ServerChatThread getThread() {
		return this.chatThread;
	}

	public void setThread(ServerChatThread paramServerChatThread) {
		this.chatThread = paramServerChatThread;
	}

	public JCheckBox getExcludeCheckBox() {
		return this.globalExcludeCheckBox;
	}

	public void sendGlobalPM(String paramString) {
		if (this.chatThread != null && !this.globalExcludeCheckBox.isSelected()) {
			specificSend("200" + getID() + JPServer.myPlayer.getID() + paramString);
			this.pmPane.addMyMessage(paramString);
		}
	}

	public String getID() {
		String str;
		if (this.PLAYER_NUMBER < 10) {
			str = "0" + this.PLAYER_NUMBER;
		} else {
			str = "" + this.PLAYER_NUMBER;
		}
		return str;
	}

	public synchronized void specificSend(String paramString) {
		if (this.chatThread == null) {
			if (this.debugSpecific) {
				JParanoia.errorMessage("No chat thread",
						getName() + " does not have a\n" + "chat thread. (Probably due to not\n" + "being logged in.)");
			}
		} else {
			this.chatThread.out.println(paramString);
		}
	}

	public void sendingGlobalPM() {
		if (this.chatThread != null) {
			if (!this.globalExcludeCheckBox.isSelected()) {
				specificSend("210" + getID());
			} else {
				specificSend("21099");
			}
		}
	}

	public void sendCharsheet() {
		logger.info("Sending " + getName() + " their char sheet...");
		specificSend("400");
		try {
			specificSend(stripComments(this.characterSheet.getText(0, this.characterSheet.getLength())));
		} catch (Exception localException) {
			logger.info("Bad location exception while sending charsheet.");
		}
		specificSend("402");
	}

	public void sendLastSavedCharsheet() {

		logger.info("Sending " + getName() + " their char sheet...");
		specificSend("400");
		try {
			specificSend(stripComments(this.characterSheet.getText(0, this.characterSheet.getLength())));
		} catch (Exception localException) {
			logger.info("Bad location exception while sending charsheet.");
		}
		specificSend("402");
	}

	// The above code within the fuction, that isn't commented, is copied from
	// 'public void sendCharsheet()' as that code still works.
	public void saveCharsheet(boolean paramBoolean) {
		String str = null;
		try {
			str = this.dataFile;
			int i = 0;
			while ((i = str.indexOf("%20")) != -1) {
				str = str.substring(0, i) + " " + str.substring(i + 3);
			}
			writer = new FileWriter(str);
			writer.write(toString() + "\n");
			writer.write(this.characterSheet.getText(0, this.characterSheet.getLength()));
			writer.flush();
			writer.close();
			charsheetSaved();
			if (paramBoolean) {
				logger.info("saveCharsheet(...): calling serverPlayer.sendCharsheet(...)");
				sendCharsheet();
				if (soundIsOn && soundMenu.charSheetAlertMenuItem.isSelected()) {
					soundPlayer.play(CHARSHEET_ALERT);
				}
			}
		} catch (Exception localException) {
			logger.info("An exception ocurred while attempting to write/send the file.");
			logger.info("RAW outputFilepath == \"" + str + "\"");
			localException.printStackTrace();
		}
	}

	public boolean hasUnsavedCharsheet() {
		return this.unsavedCharsheet;
	}

	public void charsheetUpdated() {
		numUnsavedCharsheets += 1;
		this.unsavedCharsheet = true;
		logger.info(getName() + "charsheetUpdated(): numUnsavedCharsheets == " + numUnsavedCharsheets);
	}

	public void charsheetSaved() {
		if (this.unsavedCharsheet) {
			this.unsavedCharsheet = false;
			numUnsavedCharsheets -= 1;
			logger.info(getName() + "charsheetSaved(): numUnsavedCharsheets == " + numUnsavedCharsheets);
		}
	}

	public void promptPlayerForCombatTurn() {
		specificSend("600");
	}

	public void playerAbortedTurn() {
		JPServer.absoluteSpam(getName() + ServerConstants.PLAYER_TURN_SKIPPED);
		JPServer.combatFrame.removePlayer(this);
	}

	public void kickPlayer() {
		out.print("Attempting to kick " + getName() + "... ");
		if (this.chatThread == null) {
			errorMessage("No chat thread",
					getName() + " does not have a\n" + "chat thread. (Probably due to not\n" + "being logged in.)");
			return;
		}
		this.chatThread.out.println("999*** You have been kicked ***");
		this.chatThread.disconnect(true);
		absoluteSpam(ServerConstants.KICKED_BY_SERVER_MESSAGE);
		logger.info("Player kicked.");
	}

	public void changePassword() {
		String str = null;
		if ((str = JOptionPane.showInputDialog(JPServer.frame, "Enter a new password for " + toString(), "New Password",
				JOptionPane.PLAIN_MESSAGE)) != null) {
			this.password = str;
			this.playerMenu.currentPasswordLabel.setText("    Password: " + this.password);
		}
	}

	public String toString() {
		if (this.IS_PLAYER && this.PLAYER_NUMBER != 0) {
			return getName() + "-" + this.cloneNumber;
		}
		return getName();
	}

	public void npcRename() {
		String str = null;
		if ((str = (String) JOptionPane.showInputDialog(JPServer.frame, "Enter a new name for " + toString(),
				"New Name", JOptionPane.PLAIN_MESSAGE, null, null, this.name)) != null && !str.equals("")) {
			this.name = str;
			JPServer.repaintMenus();
			this.npcMenu.setText(getName());
			JPServer.sendCommand("010" + getID() + "n" + "n" + str);
		}
	}

	@Override
	public Color getChatColor() {
		return this.chatColor;
	}
}

/*
 * Location:
 * C:\Users\noahc\Desktop\JParanoia(1.31.1)\JParanoia(1.31.1).jar!\jparanoia\
 * server\ServerPlayer.class Java compiler version: 2 (46.0) JD-Core Version:
 * 0.7.1
 */

// Items below this line are in the process of being refactored.
//-----------------------------

/*
 * public boolean isAnActualPlayer() { return this.IS_PLAYER; }
 * 
 * public NPCMenu getNpcMenu() { return this.npcMenu; }
 * 
 * public void setPMPane( PrivateMessagePane paramPrivateMessagePane ) {
 * this.pmPane = paramPrivateMessagePane; }
 * 
 * public boolean isLoggedIn() { return this.loggedIn; }
 * 
 * public void setLoggedIn( boolean paramBoolean ) { this.loggedIn =
 * paramBoolean; }
 * 
 * public Color getChatColor() { return this.chatColor; }
 * 
 * public void setChatColor( Color paramColor ) { this.chatColor = paramColor; }
 * 
 * public int getPlayerNumber() { return this.PLAYER_NUMBER; }
 * 
 * public String getRealName() { return this.realName; }
 * 
 * public void setRealName( String paramString ) { this.realName = paramString;
 * this.playerMenu.realNameLabel.setText( "    Real Name: " + this.realName ); }
 * 
 * public void setStatusPanel( StatusPanel paramStatusPanel ) { this.statusPanel
 * = paramStatusPanel; }
 * 
 * public boolean isMuted() { return this.muted; }
 * 
 * public void setMuted( boolean paramBoolean ) { this.muted = paramBoolean; }
 */

/*
 * logger.info( "Sending " + getName() + " their last saved char sheet..." );
 * specificSend( "400" ); try { final ClassLoader classLoader =
 * lookup().lookupClass().getClassLoader(); final File file = new File(
 * requireNonNull( classLoader.getResource( dataFile ) ).getFile() );
 * this.reader = new BufferedReader( new InputStreamReader( new FileInputStream(
 * file ) ) ); StringBuilder localStringBuffer = new StringBuilder(); String str
 * = this.reader.readLine(); str = this.reader.readLine(); while ( str != null )
 * { if ( !str.startsWith( "#" ) ) { localStringBuffer.append( str ).append(
 * "\n" ); } str = this.reader.readLine(); } this.reader.close(); specificSend(
 * stripComments( localStringBuffer.toString() ) ); } catch ( Exception
 * localException ) { logger.info(
 * "Bad location exception while sending charsheet." ); } specificSend( "402" );
 * }
 */
