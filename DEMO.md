# RESQGO: कल की presentation के लिए

## 1. फ़ोन तैयार करो (presentation से पहले, घर पर)

APK यहाँ बनी है:

```
C:\Ashutosh Mishra\Project\RESQGO\app\build\outputs\apk\debug\app-debug.apk
```

फ़ोन USB से जोड़कर Android Studio में **Run** दबाओ, या APK को फ़ोन में भेजकर install करो
(Install unknown apps → Allow, और Play Protect की चेतावनी पर More details → Install anyway)।

फिर app में:

1. सारी permissions **Allow** करो: Location, SMS, Phone, Notifications.
2. **Safety setup** खोलो:
   - **Your name**: अपना नाम
   - **Emergency contact**: अपना दूसरा number, या किसी दोस्त का जो साथ हो और जानता हो
   - **Wait before SOS**: **20 s** (demo में 30 s लंबा लगता है)
   - **Demo mode**: **चालू** करो
3. **Send test SOS** दबाओ। SMS पहुँचा या नहीं, ये अभी देख लो।
4. फ़ोन की Settings में RESQGO की **Battery** को **Unrestricted** करो।
5. फ़ोन का **silent mode बंद** रखो, वरना alarm दबा रहेगा।

> **ज़रूरी:** जिस number पर alert जाएगा, उस इंसान को पहले बता दो कि कल demo है।
> Message में "call 112" लिखा होता है, और कोई सच में घबराकर कॉल कर सकता है।

## 2. Stage पर क्या करना है (करीब 90 सेकंड)

| कदम | क्या करो | evaluators को क्या दिखेगा |
| --- | --- | --- |
| 1 | **Start ride** दबाओ | हरी बत्ती: "Monitoring on" |
| 2 | फ़ोन जेब में रखो, या दूसरा app खोलो | ऊपर notification: RESQGO चल रहा है |
| 3 | फ़ोन को एक बार **ज़ोर से झटका दो**, फिर मेज़ पर रख दो | ~2 सेकंड में पूरी लाल screen, तेज़ alarm |
| 4 | कुछ मत छुओ, बोलते रहो | 20 से 0 तक countdown |
| 5 | 0 पर अपने आप | "SOS sent": SMS ✓, Call placed ✓ — और फ़ोन सच में dial करेगा |
| 6 | कॉल काटो, **WhatsApp \<नाम\>** दबाओ | WhatsApp खुलेगा, message भरा हुआ, बस Send दबाना है |
| 7 | **"I'm safe, cancel SOS"** दबाओ | "I'm safe" SMS जाता है और सब रुक जाता है |

**कदम 7 मत भूलना।** वरना हर 2 मिनट पर location वाला SMS जाता रहेगा, 5 बार।

## 3. अगर झटका काम न करे (Plan B)

घबराना मत। **Safety setup → "Test the full alarm"** दबा दो। वही पूरा alarm और SOS चलता है,
बस झटके की ज़रूरत नहीं। पहले से एक बार चलाकर देख लेना।

## 4. Evaluators के सवालों के जवाब

**"WhatsApp अपने आप क्यों नहीं जाता?"**
WhatsApp किसी भी app को अपनी तरफ़ से message भेजने नहीं देता। इसलिए हम chat खोलकर message
तैयार रख देते हैं, rider सिर्फ़ Send दबाता है। बेहोश rider के लिए SMS और call हैं, जो अपने आप जाते हैं।

**"ये सच में crash पहचानता है या सिर्फ़ झटका?"**
असली mode में तीन शर्तें एक साथ पूरी होनी चाहिए: 4g से तेज़ झटका, उससे पहले 15 km/h से ऊपर की
speed, और झटके के बाद 10 सेकंड तक कोई हरकत नहीं। इसी से गड्ढा और गिरा हुआ फ़ोन अलग पहचाने
जाते हैं। कमरे में speed नहीं होती, इसलिए demo mode में सिर्फ़ झटका काफ़ी रखा है।

**"Internet चाहिए?"**
नहीं। पहचान फ़ोन पर ही होती है, और SMS और call फ़ोन के अपने SIM से जाते हैं। Data हो तो location
का link भी जाता है।

**"कितना भरोसेमंद है?"**
अभी ये pilot से पहले की stage है। 20 automated tests पास हैं, और असली सड़क पर riders के साथ
परखना अगला कदम है। Model अभी shadow mode में है: वो सीख रहा है, कोई alarm रोकता नहीं।

**"dual SIM पर चलता है?"**
हाँ। app खुद SIM चुनता है और एक fail हो तो दूसरी आज़माता है। screen पर दिखता है कि किस SIM से गया।

## 5. Demo के बाद

**Demo mode बंद कर देना।** वरना जेब में फ़ोन हिलने से भी alarm बजेगा और SOS चला जाएगा।
